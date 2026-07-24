package kio.http

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

fun Timeout(duration: Duration) : CallInterceptor = CallInterceptor { context, proceed ->
    try {
        withTimeout(duration) {
            proceed(context)
        }
    } catch (t: TimeoutCancellationException) {
        if (!context.isHeaderCommit) {
            currentLoggerOrNull()?.warn("timeout", t)
            context.respond(HttpStatusCode.GatewayTimeout)
        } else {
            throw t
        }
    }
}

