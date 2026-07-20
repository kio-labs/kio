package kio.http

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
    DEBUG,
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

fun Logger.debug(message: String) =
    log(LogLevel.DEBUG, message)

fun Logger.info(message: String) =
    log(LogLevel.INFO, message)

fun Logger.warn(
    message: String,
    cause: Throwable? = null,
) = log(LogLevel.WARN, message, cause)

fun Logger.error(
    message: String,
    cause: Throwable? = null,
) = log(LogLevel.ERROR, message, cause)

object ConsoleLogging : LoggingBackEnd {
    override fun log(
        name: String,
        level: LogLevel,
        message: String,
        cause: Throwable?,
        fields: Map<String, Any?>
    ) {
        val fieldString = if (fields.isEmpty()) {
            ""
        } else {
            fields
                .map { (key, value) -> "$key=$value" }
                .joinToString(separator = ",", prefix = "[", postfix = "]")
        }
        println("[$level] [$name] $fieldString - $message")
        cause?.printStackTrace()
    }
}
