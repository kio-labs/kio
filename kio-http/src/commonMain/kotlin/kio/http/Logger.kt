package kio.http

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
    override fun log(
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

    fun log(
        level: LogLevel,
        message: String,
        cause: Throwable? = null,
        fields: Map<String, Any?> = emptyMap()
    )
}

interface LoggingBackEnd {
    fun log(
        name: String,
        level: LogLevel,
        message: String,
        cause: Throwable? = null,
        fields: Map<String, Any?> = emptyMap()
    )
}

fun Logger.trace(message: String, fields: Map<String, Any?> = emptyMap()) =
    log(LogLevel.TRACE, message, null, fields)

fun Logger.info(message: String, fields: Map<String, Any?> = emptyMap()) =
    log(LogLevel.INFO, message, null, fields)

fun Logger.warn(
    message: String,
    cause: Throwable? = null,
    fields: Map<String, Any?> = emptyMap()
) = log(LogLevel.WARN, message, cause, fields)

fun Logger.error(
    message: String,
    cause: Throwable? = null,
    fields: Map<String, Any?> = emptyMap()
) = log(LogLevel.ERROR, message, cause, fields)

object ConsoleLogging : LoggingBackEnd {
    override fun log(
        name: String,
        level: LogLevel,
        message: String,
        cause: Throwable?,
        fields: Map<String, Any?>
    ) {
        val fieldString =
            fields.entries.joinToString(
                separator = " ",
                prefix = if (fields.isEmpty()) "" else " ",
            ) { (key, value) ->
                "$key=$value"
            }

        println("${level.name.padEnd(5)} $name: $message$fieldString")
        cause?.printStackTrace()
    }
}
