package kio.http.internal.http1

import io.ktor.http.HttpHeaders
import kio.async.buffered
import kio.async.emptyAsyncRawSource
import kio.async.io.AsyncConnection
import kotlin.time.TimeSource
import kio.http.CallContext
import kio.http.Route
import kio.http.currentLoggingBackend
import kio.http.internal.limited
import kio.http.newLogger
import kio.http.resolveHandler
import kio.http.trace
import kotlinx.coroutines.CancellationException

internal suspend fun Route.http1Connection(conn: AsyncConnection) {
    val head = conn.source.parseRequestHead()
    val (params, handler) = this.resolveHandler(head.uri, head.method)

    val fields = mapOf("protocol" to head.version.toString(), "method" to head.method, "target" to head.uri)
    val logger = currentLoggingBackend().newLogger("HttpCall", extra = fields)
    val started = TimeSource.Monotonic.markNow()
    logger.trace("Request headers decoded")

    val contentLength = head.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    val encoding = head.headers[HttpHeaders.TransferEncoding]

// TODO: do wrap source in CallInterceptor.
    val body = when {
        encoding == "chunked" -> conn.source.chunked()
        contentLength > 0 -> conn.source.limited(contentLength)
        else -> emptyAsyncRawSource()
    }

    val callContext = CallContext(
        conn,
        requestHead = head,
        body = body,
        parameters = params,
        logger = logger,
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
        try {
            handler?.invoke(callContext)
        } finally {
            callContext.requestBody?.close()
        }

        callContext.responseSink.flush()
        callContext.responseSink.close()

        logger.trace("Response sent", mapOf(
            "status" to callContext.responseHead.statusCode.value,
            "durationMs" to started.elapsedNow().inWholeMilliseconds,
        ))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // The connection boundary reports the exception; retain request context here.
        logger.trace("Request failed", mapOf(
            "reason" to t.toString(),
            "headersCommitted" to callContext.isHeaderCommit,
            "durationMs" to started.elapsedNow().inWholeMilliseconds,
        ))
        throw t
    }
}
