package kio.http

import kio.async.AsyncSink
import kio.async.SuspendIo
import kio.async.poller
import kio.async.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

suspend fun currentLoggerOrNull(): Logger? {
    return currentCoroutineContext()[CoroutineLogger]?.logger
}

suspend fun currentLogger(): Logger {
    return currentCoroutineContext()[CoroutineLogger]?.logger ?: error("logger not set")
}

data class CoroutineLogger(
    val logger: Logger
) : AbstractCoroutineContextElement(CoroutineLogger) {
    companion object Key : CoroutineContext.Key<CoroutineLogger>

    override fun toString(): String = "CoroutineLogger(${logger.loggerName})"
}

suspend fun currentLoggingBackend(): LoggingBackEnd {
    return currentCoroutineContext()[CoroutineLoggingBackend]?.loggingBackEnd ?: error("CoroutineLoggingBackend not set")
}

internal data class CoroutineLoggingBackend(
    val loggingBackEnd: LoggingBackEnd
) : AbstractCoroutineContextElement(CoroutineLoggingBackend) {
    companion object Key : CoroutineContext.Key<CoroutineLoggingBackend>

    override fun toString(): String = "CoroutineLoggingBackend(${loggingBackEnd})"
}

enum class LogLevel {
    TRACE,
    INFO,
    WARN,
    ERROR,
    ;
}

fun LoggingBackEnd.newLogger(
    name: String,
    extra: Map<String, Any?> = emptyMap(),
    logWrapper: suspend (suspend () -> Unit) -> Unit = { it() }
): Logger = object : Logger {
    override val loggerName: String = name
    override suspend fun log(
        level: LogLevel,
        message: String,
        cause: Throwable?,
        fields: Map<String, Any?>
    ) {
        logWrapper {
            log(loggerName, level, message, cause, fields + extra)
        }
    }
}

interface Logger {
    val loggerName: String

    suspend fun log(
        level: LogLevel,
        message: String,
        cause: Throwable? = null,
        fields: Map<String, Any?> = emptyMap()
    )
}

interface LoggingBackEnd {
    suspend fun log(
        name: String,
        level: LogLevel,
        message: String,
        cause: Throwable? = null,
        fields: Map<String, Any?> = emptyMap()
    )
}

suspend fun Logger.trace(message: String, fields: Map<String, Any?> = emptyMap()) =
    log(LogLevel.TRACE, message, null, fields)

suspend fun Logger.info(message: String, fields: Map<String, Any?> = emptyMap()) =
    log(LogLevel.INFO, message, null, fields)

suspend fun Logger.warn(
    message: String,
    cause: Throwable? = null,
    fields: Map<String, Any?> = emptyMap()
) = log(LogLevel.WARN, message, cause, fields)

suspend fun Logger.error(
    message: String,
    cause: Throwable? = null,
    fields: Map<String, Any?> = emptyMap()
) = log(LogLevel.ERROR, message, cause, fields)

private data class LogEntry(
    val level: LogLevel,
    val text: String,
)

class AsyncLoggingBackEnd(
    scope: CoroutineScope,
    capacity: Int = 1024,
    private val batchSize: Int = 64,
    private val enableTrace: Boolean = false,
) : LoggingBackEnd {
    init {
        require(capacity > 0)
        require(batchSize > 0)
    }

    private val queue = Channel<LogEntry>(capacity)

    private val writer = scope.async {
        val io = currentCoroutineContext().poller.io
        val outSink = io.stdOutAsyncSink()
        val errSink = io.stdErrorAsyncSink()

        suspend fun writeOnce(entry: LogEntry) {
            val (level, content) = entry
            val sink = if (level == LogLevel.ERROR) errSink else outSink
            sink.writeString(content)
        }

        try {
            for (entry in queue) {
                writeOnce(entry)

                var count = 1
                while (count < batchSize) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    writeOnce(next)
                    count++
                }

                outSink.flush()
                errSink.flush()
            }
        } finally {
            queue.cancel()
        }
    }

    override suspend fun log(
        name: String,
        level: LogLevel,
        message: String,
        cause: Throwable?,
        fields: Map<String, Any?>,
    ) {
        if (!enableTrace && level == LogLevel.TRACE) {
            return
        }

        val text = buildString {
            append(level.name.padEnd(5))
            append(' ')
            append(name)
            append(": ")
            append(message)

            for ((key, value) in fields) {
                append(' ')
                append(key)
                append('=')
                append(value)
            }

            append('\n')
            cause?.let { append(it.stackTraceToString()) }
        }

        queue.send(LogEntry(level, text))
    }

    suspend fun close() {
        queue.close()
        writer.await()
    }
}

expect fun SuspendIo.stdOutAsyncSink(): AsyncSink
expect fun SuspendIo.stdErrorAsyncSink(): AsyncSink