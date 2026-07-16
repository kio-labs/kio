package kio.http.internal.http1

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kio.async.buffered
import kio.async.io.AsyncConnection
import kio.http.CallContext
import kio.http.RouteScope
import kio.http.internal.limited
import kio.http.respond
import kotlinx.coroutines.CancellationException

internal suspend fun RouteScope.http1Connection(conn: AsyncConnection) {
    val head = conn.source.parseRequestHead()
    val handler = getCallHandler(RouteScope.RouteKey(head.method, head.uri))

    val contentLength = head.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    val encoding = head.headers[HttpHeaders.TransferEncoding]

// TODO: do wrap source in CallInterceptor.
    val body = when {
        encoding == "chunked" -> conn.source.chunked()
        contentLength > 0 -> conn.source.limited(contentLength)
        else -> null
    }

    val callContext = CallContext(
        conn,
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
        println("exception happened $t")
        callContext.respond(HttpStatusCode.InternalServerError, t.toString())
    } finally {
        callContext.requestBody?.close()
    }

    // write response
    callContext.responseSink.flush()
    callContext.responseSink.close()
}
