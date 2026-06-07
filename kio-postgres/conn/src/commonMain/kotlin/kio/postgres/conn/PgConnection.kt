package kio.postgres.conn

import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.network.AsyncConnection
import kio.network.openConnection
import kio.postegre.protocol.ErrorField
import kio.postegre.protocol.Message
import kio.postegre.protocol.readMessage
import kio.postegre.protocol.writeBind
import kio.postegre.protocol.writeCancelRequest
import kio.postegre.protocol.writeCloseStatement
import kio.postegre.protocol.writeCopyDone
import kio.postegre.protocol.writeCopyFail
import kio.postegre.protocol.writeExecute
import kio.postegre.protocol.writeParse
import kio.postegre.protocol.writeQuery
import kio.postegre.protocol.writeSync
import kio.postegre.protocol.writeTerminate
import kio.postegre.types.PostgresFormat
import kio.postegre.types.formats
import kio.postegre.types.typeOids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext

interface PgConnection {
    suspend fun waitNotification(): PgNotification
    suspend fun copyTo(sql: String, sink: Sink): String
    suspend fun copyFrom(sql: String, source: Source): String
    suspend fun copyFrom(sql: String, source: AsyncSource): String
    suspend fun close()
}

interface PgStatement {
    val name: String
    suspend fun close()
}

data class PgNotification(
    val processId: Int,
    val channel: String,
    val message: String
)

inline fun <reified R> PgConnection.query(sql: String): Flow<R> {
    return query(sql, Unit)
}

inline fun <reified P, reified R> PgConnection.query(sql: String, params: P): Flow<R> {
    this as InternalPgConnection
    val conn = this
    return flow {
        withLock {
            val resultSerializer = PostgresFormat.serializersModule.serializer<R>()
            conn.sink.doQuery(sql, params, resultSerializer.formats(), true)
            emitRowUntilReadyOrThrow(conn, resultSerializer)
        }
    }
}

inline fun <reified P, reified R> PgConnection.query(stmt: PgStatement, params: P): Flow<R> {
    this as InternalPgConnection

    val conn = this
    return flow {
        withLock {
            val parameterSerializer = PostgresFormat.serializersModule.serializer<P>()
            val resultSerializer = PostgresFormat.serializersModule.serializer<R>()
            val values = PostgresFormat.encodeToByteArray(parameterSerializer, params)
            val parameterFormats = parameterSerializer.formats()
            val resultFormats = resultSerializer.formats()
            execStmt(sink, stmt.name, values, parameterFormats, resultFormats)

            emitRowUntilReadyOrThrow(conn, resultSerializer)
        }
    }
}

suspend inline fun <reified P> PgConnection.exec(stmt: PgStatement, params: P): String {
    this as InternalPgConnection

    return withLock {
        val parameterSerializer = PostgresFormat.serializersModule.serializer<P>()
        val values = PostgresFormat.encodeToByteArray(parameterSerializer, params)
        val parameterFormats = parameterSerializer.formats()
        val resultFormats = listOf<Short>()
        execStmt(sink, stmt.name, values, parameterFormats, resultFormats)

        waitReadyAndCollectCommandTags().lastOrNull() ?: ""
    }
}

suspend inline fun <reified P> PgConnection.exec(sql: String, params: P): String {
    this as InternalPgConnection

    return withLock {
        sink.doQuery(sql, params, listOf(), true)

        waitReadyAndCollectCommandTags().lastOrNull() ?: ""
    }
}

suspend fun PgConnection.exec(sql: String): String {
    this as InternalPgConnection

    return withLock {
        sink.writeQuery(sql)
        sink.flush()

        waitReadyAndCollectCommandTags().lastOrNull() ?: ""
    }
}

suspend fun PgConnection.prepare(sql: String, name: String): PgStatement {
    return prepare<Unit>(sql, name)
}

suspend inline fun <reified P> PgConnection.prepare(sql: String, name: String): PgStatement {
    this as InternalPgConnection

    val parameterSerializer = PostgresFormat.serializersModule.serializer<P>()
    val paramTypes = parameterSerializer.typeOids()

    return withLock {
        sink.writeParse(name, sql, paramTypes)
        sink.writeSync()
        sink.flush()

        waitReadyOrThrow()

        InternalPgStatement(name, this)
    }
}

class PgException(
    val severity: String? = null,
    val severityUnlocalized: String? = null,
    val code: String? = null,
    val pgMessage: String? = null,
    val detail: String? = null,
    val hint: String? = null,
    val position: String? = null,
    val internalPosition: String? = null,
    val internalQuery: String? = null,
    val where: String? = null,
    val schemaName: String? = null,
    val tableName: String? = null,
    val columnName: String? = null,
    val dataTypeName: String? = null,
    val constraintName: String? = null,
    val file: String? = null,
    val line: String? = null,
    val routine: String? = null,
    val unknownFields: String? = null,
) : IOException(
    message = buildString {
        appendLine()
        severity?.let { appendLine("Code: $it") }
        severityUnlocalized?.let { appendLine("ColumnName: $it") }
        code?.let { appendLine("ConstraintName: $it") }
        pgMessage?.let { appendLine("DataTypeName: $it") }
        detail?.let { appendLine("Detail: $it") }
        hint?.let { appendLine("File: $it") }
        position?.let { appendLine("Hint: $it") }
        internalPosition?.let { appendLine("InternalPosition: $it") }
        internalQuery?.let { appendLine("InternalQuery: $it") }
        where?.let { appendLine("Line: $it") }
        schemaName?.let { appendLine("Message: $it") }
        tableName?.let { appendLine("Position: $it") }
        columnName?.let { appendLine("Routine: $it") }
        dataTypeName?.let { appendLine("SchemaName: $it") }
        constraintName?.let { appendLine("Severity: $it") }
        file?.let { appendLine("SeverityUnlocalized: $it") }
        line?.let { appendLine("TableName: $it") }
        routine?.let { appendLine("UnknownFields: $it") }
        unknownFields?.let { appendLine("Where: $it") }
    }
)

@PublishedApi
internal suspend fun execStmt(
    sink: AsyncSink,
    stmtName: String,
    values: ByteArray,
    parameterFormats: List<Short>,
    resultFormats: List<Short>,
    sync: Boolean = true
) {
    sink.writeBind(
        statement = stmtName,
        formats = parameterFormats,
        values = values,
        resultFormat = resultFormats,
    )
    sink.writeExecute("")

    if (sync) {
        sink.writeSync()
        sink.flush()
    }
}

@PublishedApi
internal suspend inline fun <reified P> AsyncSink.doQuery(
    sql: String,
    params: P,
    resultFormats: List<Short>,
    sync: Boolean
) {
    val parameterSerializer = PostgresFormat.serializersModule.serializer<P>()
    val values = PostgresFormat.encodeToByteArray(parameterSerializer, params)
    val paramTypes = parameterSerializer.typeOids()
    val parameterFormats = parameterSerializer.formats()
    execParams(this, sql, values, paramTypes, parameterFormats, resultFormats, sync = sync)
}

@PublishedApi
internal suspend fun execParams(
    sink: AsyncSink,
    sql: String,
    values: ByteArray,
    paramTypes: List<Int>,
    parameterFormats: List<Short>,
    resultFormats: List<Short>,
    sync: Boolean
) {
    sink.writeParse("", sql, paramTypes)
    sink.writeBind(
        formats = parameterFormats,
        values = values,
        resultFormat = resultFormats,
    )
    sink.writeExecute("")

    if (sync) {
        sink.writeSync()
        sink.flush()
    }
}

@PublishedApi
internal class InternalPgStatement(
    override val name: String,
    val conn: InternalPgConnection
) : PgStatement {
    override suspend fun close() {
        conn.withLock {
            conn.sink.writeCloseStatement(name)
            conn.sink.writeSync()
            conn.sink.flush()

            conn.waitReadyOrThrow()
        }
    }
}

@PublishedApi
internal class InternalPgConnection(
    parentContext: CoroutineContext,
    private val conn: AsyncConnection,
    private val parameterStatuses: MutableMap<String, String> = mutableMapOf(),
    private val host: String,
    private val port: Int,
    private val pid: Int,
    private val secretKey: ByteArray,
    private val onNotice: (PgException) -> Unit,
) : PgConnection, CoroutineScope {
    val sink = conn.sink
    private val source = conn.source
    val mutex = Mutex()

    override val coroutineContext: CoroutineContext = parentContext + Job()

    private val messageChannel = Channel<Message>()
    private val notificationSharedFlow = MutableSharedFlow<PgNotification>()

    init {
        launchMessageDispatchLoop()
    }

    private fun launchMessageDispatchLoop() = launch {
        while (true) {
            when (val msg = source.readMessage()) {
// Query result
                Message.BindComplete,
                Message.CloseComplete,
                is Message.CommandComplete,
                is Message.CopyData,
                Message.CopyDone,
                is Message.CopyInResponse,
                is Message.CopyOutResponse,
                is Message.DataRow,
                Message.EmptyQueryResponse,
                is Message.ErrorResponse,
                Message.NoData,
                is Message.ParameterDescription,
                Message.ParseComplete,
                Message.PortalSuspended,
                is Message.ReadyForQuery,
                is Message.RowDescription -> messageChannel.send(msg)
// Server notification
                is Message.ParameterStatus -> parameterStatuses[msg.name] = msg.value
                is Message.NoticeResponse -> onNotice(buildPgException(msg.errors))
                is Message.NotificationResponse -> notificationSharedFlow.emit(PgNotification(msg.processId, msg.channel, msg.message))
// Ignore Authentication message
                else -> Unit
            }
        }
    }

    suspend fun readMessage() = messageChannel.receive()

    suspend inline fun <T> withLock(block: () -> T): T {
        mutex.lock()
        return try {
            block()
        } catch (cancellationException: CancellationException) {
            sendCancelRequest()
            waitReadyAfterCancel()
            throw cancellationException
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun waitNotification(): PgNotification {
        return notificationSharedFlow.first()
    }

    override suspend fun copyTo(sql: String, sink: Sink) = withLock {
        conn.sink.writeQuery(sql)
        conn.sink.flush()

        var commandTag: String? = null
        while (true) {
            when (val msg = readMessage()) {
                Message.CopyDone -> {}
                is Message.CopyData -> {
                    sink.write(msg.data)
                }

                is Message.ReadyForQuery -> break
                is Message.CommandComplete -> commandTag = msg.tag
                is Message.ErrorResponse -> throw buildPgException(msg.errors)
                else -> {}
            }
        }

        commandTag ?: error("no command tag")
    }

    override suspend fun copyFrom(sql: String, source: Source) = withLock {
        copyFrom(sql, { source.readAtMostTo(it) })
    }

    override suspend fun copyFrom(sql: String, source: AsyncSource): String = withLock {
        copyFrom(sql, { source.readAtMostTo(it) })
    }

    private suspend inline fun copyFrom(
        sql: String,
        crossinline readFunc: suspend (ByteArray) -> Int
    ): String = coroutineScope {
        conn.sink.writeQuery(sql)
        conn.sink.flush()

        // Wait copy in response
        var error: Message.ErrorResponse? = null
        while (true) {
            when (val message = readMessage()) {
                is Message.ErrorResponse -> error = message
                is Message.CopyInResponse -> if (error != null) throw buildPgException(error.errors) else break
                else -> {}
            }
        }

        val buf = ByteArray(8 * 1024)

        var readException: IOException? = null
        val sendJob = launch {
            try {
                while (true) {
                    val read = readFunc(buf)

                    if (read == -1) break

                    conn.sink.writeByte('d'.code.toByte())
                    conn.sink.writeInt(read + 4)
                    conn.sink.write(buf, 0, read)
                    conn.sink.flush()
                }
            } catch (e: CancellationException) {
                // cancellation when sending data, send copy failed to server.
                withContext(NonCancellable) {
                    conn.sink.writeCopyFail(e.message ?: "cancelled")
                    conn.sink.flush()
                }
                throw e
            } catch (e: IOException) {
                readException = e
            }
        }

        var pgError: Message.ErrorResponse? = null
        val receiveJob = launch {
            while (true) {
                when (val msg = readMessage()) {
                    is Message.ErrorResponse -> {
                        pgError = msg
                        break
                    }

                    else -> {}
                }
            }
        }

        receiveJob.invokeOnCompletion { sendJob.cancel() }
        sendJob.invokeOnCompletion { receiveJob.cancel() }

        joinAll(receiveJob, sendJob)

        if (readException == null || pgError != null) {
            conn.sink.writeCopyDone()
        } else {
            conn.sink.writeCopyFail(readException.message ?: "Unknown err")
        }
        conn.sink.flush()

        val commandTags = mutableListOf<String>()
        while (true) {
            when (val message = readMessage()) {
                is Message.ErrorResponse -> pgError = message
                is Message.CommandComplete -> commandTags.add(message.tag)
                is Message.ReadyForQuery -> if (pgError != null) throw buildPgException(pgError.errors) else break
                else -> {}
            }
        }

        commandTags.lastOrNull() ?: ""
    }

    override suspend fun close() {
        cancel()
        sink.writeTerminate()
        sink.flush()

        conn.close()
    }

    suspend fun waitReadyAfterCancel() = withContext(NonCancellable) {
        var pgException: PgException? = null
        while (true) {
            when (val message = readMessage()) {
                is Message.ErrorResponse -> {
                    val exception = buildPgException(message.errors)
                    if (exception.code == "57014") {
                        // ignore cancel error
                    } else {
                        pgException = exception
                    }
                }

                is Message.ReadyForQuery -> if (pgException != null) throw pgException else break
                else -> {}
            }
        }
    }

    suspend fun sendCancelRequest() {
        withContext(NonCancellable) {
            val conn = openConnection(host, port)
            try {
                conn.sink.writeCancelRequest(processId = pid, secretKey = secretKey)
                conn.sink.flush()
            } finally {
                conn.close()
            }
        }
    }
}

@PublishedApi
internal suspend fun InternalPgConnection.waitReadyAndCollectCommandTags(): List<String> {
    val commandTags = mutableListOf<String>()
    var error: Message.ErrorResponse? = null
    while (true) {
        when (val message = readMessage()) {
            is Message.ErrorResponse -> error = message
            is Message.CommandComplete -> commandTags.add(message.tag)
            is Message.ReadyForQuery -> if (error != null) throw buildPgException(error.errors) else break
            else -> {}
        }
    }

    return commandTags
}

@PublishedApi
internal suspend fun InternalPgConnection.waitReadyOrThrow() {
    var error: Message.ErrorResponse? = null
    while (true) {
        when (val message = readMessage()) {
            is Message.ErrorResponse -> error = message
            is Message.ReadyForQuery -> if (error != null) throw buildPgException(error.errors) else break
            else -> {}
        }
    }
}

@PublishedApi
internal suspend fun <T> FlowCollector<T>.emitRowUntilReadyOrThrow(
    conn: InternalPgConnection,
    resultSerializer: KSerializer<T>
) {
    var error: Message.ErrorResponse? = null
    while (true) {
        when (val message = conn.readMessage()) {
            is Message.DataRow -> emit(
                PostgresFormat.decodeFromByteArray(
                    resultSerializer,
                    message.byteArray
                )
            )

            is Message.ErrorResponse -> error = message
            is Message.ReadyForQuery -> if (error != null) throw buildPgException(error.errors) else break

            else -> {}
        }
    }
}

internal fun buildPgException(errors: Iterable<ErrorField>): PgException {
    var severity: String? = null
    var severityUnlocalized: String? = null
    var code: String? = null
    var pgMessage: String? = null
    var detail: String? = null
    var hint: String? = null
    var position: String? = null
    var internalPosition: String? = null
    var internalQuery: String? = null
    var where: String? = null
    var schemaName: String? = null
    var tableName: String? = null
    var columnName: String? = null
    var dataTypeName: String? = null
    var constraintName: String? = null
    var file: String? = null
    var line: String? = null
    var routine: String? = null
    var unknownFields: String? = null
    errors.forEach { errorField ->
        when (errorField) {
            is ErrorField.Severity -> severity = errorField.value
            is ErrorField.SeverityUnlocalized -> severityUnlocalized = errorField.value
            is ErrorField.Code -> code = errorField.value
            is ErrorField.Message -> pgMessage = errorField.value
            is ErrorField.Detail -> detail = errorField.value
            is ErrorField.Hint -> hint = errorField.value
            is ErrorField.Position -> position = errorField.value
            is ErrorField.InternalPosition -> internalPosition = errorField.value
            is ErrorField.InternalQuery -> internalQuery = errorField.value
            is ErrorField.Where -> where = errorField.value
            is ErrorField.SchemaName -> schemaName = errorField.value
            is ErrorField.TableName -> tableName = errorField.value
            is ErrorField.ColumnName -> columnName = errorField.value
            is ErrorField.DataTypeName -> dataTypeName = errorField.value
            is ErrorField.ConstraintName -> constraintName = errorField.value
            is ErrorField.File -> file = errorField.value
            is ErrorField.Line -> line = errorField.value
            is ErrorField.Routine -> routine = errorField.value
            is ErrorField.UnknownFields -> unknownFields = errorField.value
        }
    }
    return PgException(
        severity = severity,
        severityUnlocalized = severityUnlocalized,
        code = code,
        pgMessage = pgMessage,
        detail = detail,
        hint = hint,
        position = position,
        internalPosition = internalPosition,
        internalQuery = internalQuery,
        where = where,
        schemaName = schemaName,
        tableName = tableName,
        columnName = columnName,
        dataTypeName = dataTypeName,
        constraintName = constraintName,
        file = file,
        line = line,
        routine = routine,
        unknownFields = unknownFields,
    )
}