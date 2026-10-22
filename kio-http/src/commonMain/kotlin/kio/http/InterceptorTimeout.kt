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
            context.logger?.warn("Request timed out; responding with HTTP 504", fields = mapOf("timeout" to duration))
            context.respond(HttpStatusCode.GatewayTimeout)
        } else {
            throw t
        }
    }
}

