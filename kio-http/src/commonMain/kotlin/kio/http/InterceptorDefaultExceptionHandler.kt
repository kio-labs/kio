package kio.http

import io.ktor.http.HttpStatusCode

val DefaultExceptionHandler: CallInterceptor = CallInterceptor { context, proceed ->
    try {
        proceed(context)
    } catch (t: Throwable) {
        if (!context.isHeaderCommit) {
            currentLoggerOrNull()?.warn("handler exception caught by DefaultExceptionHandler", t)
            context.respond(HttpStatusCode.InternalServerError)
        } else {
            throw t
        }
    }
}