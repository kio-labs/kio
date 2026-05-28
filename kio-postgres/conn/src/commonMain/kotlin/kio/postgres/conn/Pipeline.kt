package kio.postgres.conn

import kio.postegre.protocol.Message
import kio.postegre.protocol.writeQuery
import kio.postegre.protocol.writeSync
import kio.postegre.types.PostgresFormat
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

suspend inline fun <reified T> PgConnection.pipeline(block: suspend PipelineScope.() -> T): T {
    this as InternalPgConnection

    return withLock {
        val scope = InternalPipelineScope(this)
        scope.block()
            .also { scope.throwIfNeeded() }
    }
}

interface PipelineScope {
    suspend fun withSync(block: suspend PipelineSendScope.() -> Unit)
    suspend fun <T> consumeSync(block: suspend PipelineReceiveScope.() -> T): T?
}

interface PipelineReceiveScope {
    suspend fun receive()
}

interface PipelineSendScope {
    suspend fun exec(sql: String)
}

suspend fun PipelineSendScope.query(sql: String) {
    this as InternalPipelineSendScope

    query(sql, Unit)
}

suspend inline fun <reified P> PipelineSendScope.query(sql: String, params: P) {
    this as InternalPipelineSendScope

    val resultFormats = listOf<Short>(1)
    conn.sink.doQuery(sql, params, resultFormats, false)
}

suspend inline fun <reified R> PipelineReceiveScope.receive(collector: FlowCollector<R>) {
    this as InternalPipelineReceiveScope

    val resultSerializer = PostgresFormat.serializersModule.serializer<R>()
    collector.emitRowUntilCommandDoneOrThrow(conn, resultSerializer)
}

suspend inline fun <reified R> PipelineReceiveScope.receiveAsList(): List<R> {
    this as InternalPipelineReceiveScope

    val resultSerializer = PostgresFormat.serializersModule.serializer<R>()
    return flow { emitRowUntilCommandDoneOrThrow(conn, resultSerializer) }.toList()
}

@PublishedApi
internal class InternalPipelineSendScope(
    val conn: InternalPgConnection
) : PipelineSendScope {
    override suspend fun exec(sql: String) {
        conn.sink.writeQuery(sql)
    }
}

@PublishedApi
internal class InternalPipelineReceiveScope(
    val conn: InternalPgConnection
) : PipelineReceiveScope {
    override suspend fun receive() {
        conn.waitCommandDoneOrThrow()
    }
}

@PublishedApi
internal class InternalPipelineScope(
    val conn: InternalPgConnection
) : PipelineScope {
    private val exceptions = mutableListOf<PgException>()

    private val receiveScope = InternalPipelineReceiveScope(conn)
    private val sendScope = InternalPipelineSendScope(conn)

    override suspend fun withSync(block: suspend PipelineSendScope.() -> Unit) {
        block(sendScope)
        conn.sink.writeSync()
        conn.sink.flush()
    }

    override suspend fun <T> consumeSync(block: suspend PipelineReceiveScope.() -> T): T? {
        var exception: PgException? = null
        val result = try {
            block(receiveScope)
        } catch (e: PgException) {
            exception = e
            null
        }

        // ignore events until ReadyForQuery because receive action already performed.
        while (true) {
            when (conn.readMessage()) {
                is Message.ReadyForQuery -> break
                else -> {
                }
            }
        }
        if (exception != null) recordExceptions(exception)

        return result
    }

    private fun recordExceptions(e: PgException) {
        exceptions.add(e)
    }

    fun throwIfNeeded() {
// TODO: throw if sync not be consumed.
        if (exceptions.isNotEmpty()) {
// TODO: fold all exceptions
            throw exceptions.first()
        }
    }
}

@PublishedApi
internal suspend fun <T> FlowCollector<T>.emitRowUntilCommandDoneOrThrow(
    conn: InternalPgConnection,
    resultSerializer: KSerializer<T>
) {
    while (true) {
        when (val message = conn.readMessage()) {
            is Message.DataRow -> {
                emit(
                    PostgresFormat.decodeFromByteArray(
                        resultSerializer,
                        message.byteArray
                    )
                )
            }

            is Message.ErrorResponse -> throw buildPgException(message.errors)
            is Message.CommandComplete -> break

            else -> {}
        }
    }
}

@PublishedApi
internal suspend fun InternalPgConnection.waitCommandDoneOrThrow() {
    while (true) {
        when (val message = readMessage()) {
            is Message.ErrorResponse -> throw buildPgException(message.errors)
            is Message.CommandComplete -> break
            else -> {}
        }
    }
}
