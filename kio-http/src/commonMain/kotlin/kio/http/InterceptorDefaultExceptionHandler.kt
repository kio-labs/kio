package kio.http

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

val DefaultExceptionHandler: CallInterceptor = CallInterceptor { context, proceed ->
    try {
        proceed(context)
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        if (!context.isHeaderCommit) {
            context.logger?.error("Request handler failed; responding with HTTP 500", t)
            context.respond(HttpStatusCode.InternalServerError)
        } else {
            throw t
        }
    }
}