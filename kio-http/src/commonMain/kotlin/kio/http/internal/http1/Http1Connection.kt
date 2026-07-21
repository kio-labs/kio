package kio.http.internal.http1

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kio.async.buffered
import kio.async.io.AsyncConnection
import kio.http.CallContext
import kio.http.Route
import kio.http.currentLoggingBackend
import kio.http.internal.limited
import kio.http.newLogger
import kio.http.resolveHandler
import kio.http.respond
import kio.http.trace
import kio.http.warn
import kotlinx.coroutines.CancellationException

internal suspend fun Route.http1Connection(conn: AsyncConnection) {
    val logger = currentLoggingBackend().newLogger("HTTP1")

    val head = conn.source.parseRequestHead()
    val (params, handler) = this.resolveHandler(head.uri, head.method)

    val fields = mapOf("method" to head.method, "target" to head.uri)
    logger.trace("request head decoded.", fields)

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
        parameters = params,
        responseSink = { head, trailer ->
// TODO: send trailer for http1
            Http1ResponseSink(
                head = head,
                sink = conn.sink,
                onHeaderCommit = {
                    isHeaderCommit = true
                }
            ).buffered()
        }
    )

    try {
        handler?.invoke(callContext)
        logger.trace("handled request success.")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        logger.warn("handled request failed.", t, fields)
    } finally {
        callContext.requestBody?.close()
    }

    // write response
    callContext.responseSink.flush()
    callContext.responseSink.close()

    logger.trace("handled request finished.")
}
