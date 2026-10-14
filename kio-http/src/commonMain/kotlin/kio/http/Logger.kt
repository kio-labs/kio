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

fun LoggingBackEnd.newLogger(name: String, extra: Map<String, Any?> = emptyMap()): Logger = object : Logger {
    override val loggerName: String = name
    override suspend  fun log(
        level: LogLevel,
        message: String,
        cause: Throwable?,
        fields: Map<String, Any?>
    ) {
        log(loggerName, level, message, cause, fields + extra)
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

class AsyncLoggingBackEnd(
    scope: CoroutineScope,
    capacity: Int = 1024,
    private val batchSize: Int = 64,
) : LoggingBackEnd {
    init {
        require(capacity > 0)
        require(batchSize > 0)
    }

    private val queue = Channel<String>(capacity)
    private var _sink: AsyncSink? = null
    private val sink get() = _sink ?: error("never")

    private val writer = scope.async {
        try {
            for (first in queue) {
                sink.writeString(first)

                var count = 1
                while (count < batchSize) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    sink.writeString(next)
                    count++
                }

                sink.flush()
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
        if (_sink == null) {
            _sink = currentCoroutineContext().poller.io.stdOutAsyncSink()
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

        queue.send(text)
    }

    suspend fun close() {
        queue.close()
        writer.await()
    }
}

expect fun SuspendIo.stdOutAsyncSink(): AsyncSink