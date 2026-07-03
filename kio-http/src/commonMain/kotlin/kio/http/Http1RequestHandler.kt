package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kio.async.buffered
import kio.http.internal.HttpRequestHead
import kio.http.internal.http1.chunked
import kio.http.internal.http1.http1ResponseSink
import kio.http.internal.limited
import kio.network.AsyncConnection
import kotlinx.coroutines.CancellationException

internal suspend fun RouteScope.handleHttp1Request(
    head: HttpRequestHead,
    conn: AsyncConnection,
) {
    val handler = getCallHandler(RouteScope.RouteKey(head.method, head.uri))

    doHandleHttp1Request(head, conn, handler)
}

internal suspend fun doHandleHttp1Request(
    head: HttpRequestHead,
    conn: AsyncConnection,
    handler: CallHandler?,
    isTest: Boolean = false
) {
    val contentLength = head.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    val encoding = head.headers[HttpHeaders.TransferEncoding]

// TODO: do wrap source in CallInterceptor.
    val body = when {
        encoding == "chunked" -> conn.source.chunked()
        contentLength > 0 -> conn.source.limited(contentLength)
        else -> null
    }

    val callContext = CallContext(
        head, body,
        responseSink = { head, trailer ->
// TODO: send trailer for http1
            conn.sink.http1ResponseSink(head).buffered()
        }
    )

    try {
        handler?.invoke(callContext)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        if (isTest) throw t
        println("exception happened $t")
        callContext.respond(HttpStatusCode.InternalServerError, t.toString())
    } finally {
        callContext.requestBody?.close()
    }

    // write response
    callContext.responseSink.flush()
    callContext.responseSink.close()
}
