package kio.http.internal.http1

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kio.async.buffered
import kio.async.emptyAsyncRawSource
import kio.async.io.AsyncConnection
import kio.http.CallContext
import kio.http.Route
import kio.http.currentLoggingBackend
import kio.http.internal.HttpRequestHead
import kio.http.internal.limited
import kio.http.isBodylessResponse
import kio.http.newLogger
import kio.http.resolveHandler
import kio.http.trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.EOFException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

suspend fun Route.http1Connection(
    conn: AsyncConnection,
    keepAliveTimeout: Duration = 60.seconds,
    maxKeepAliveRequests: Int = 1000,
) {
    var requestCount = 0
    while (true) {
        val head = try {
            if (requestCount == 0 || keepAliveTimeout == Duration.INFINITE) {
                conn.source.parseRequestHead()
            } else {
                withTimeoutOrNull(keepAliveTimeout) {
                    conn.source.parseRequestHead()
                } ?: break
            }
        } catch (e: EOFException) {
            if (requestCount == 0) {
                throw e
            }
            break
        }

        requestCount++
        val reachedMaxRequests = maxKeepAliveRequests > 0 && requestCount >= maxKeepAliveRequests
        val keepAlive = handleHttp1Request(conn, head, forceClose = reachedMaxRequests)
        if (!keepAlive) {
            break
        }
    }
}

private suspend fun Route.handleHttp1Request(
    conn: AsyncConnection,
    head: HttpRequestHead,
    forceClose: Boolean,
): Boolean {
    val (params, handler) = this.resolveHandler(head.uri, head.method)

    val fields = mapOf("protocol" to head.version.toString(), "method" to head.method, "target" to head.uri)
    val logger = currentLoggingBackend().newLogger("HttpCall", extra = fields)
    val started = TimeSource.Monotonic.markNow()
    logger.trace("Request headers decoded")

    var keepAlive = !forceClose && "close" !in head.headers.connectionTokens()

    val contentLength = head.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    val encoding = head.headers[HttpHeaders.TransferEncoding]

// TODO: do wrap source in CallInterceptor.
    val body = when {
        encoding.equals("chunked", ignoreCase = true) -> conn.source.chunked()
        contentLength > 0 -> conn.source.limited(contentLength)
        else -> emptyAsyncRawSource()
    }

    val callContext = CallContext(
        conn,
        requestHead = head,
        body = body,
        parameters = params,
        logger = logger,
        responseSink = { responseHead, _ ->
// TODO: send trailer for http1
            Http1ResponseSink(
                head = responseHead,
                sink = conn.sink,
                onHeaderCommit = {
                    isHeaderCommit = true
                }
            ).buffered()
        }
    )

    if (!keepAlive) {
        callContext.responseHead.headers[HttpHeaders.Connection] = "close"
    }

    try {
        try {
            handler?.invoke(callContext)
        } finally {
            if (callContext.responseHead.statusCode == HttpStatusCode.PayloadTooLarge) {
                keepAlive = false
            }
            if (keepAlive) {
                callContext.requestBody.close()
            }
        }

        val status = callContext.responseHead.statusCode
        val isUpgrade = status == HttpStatusCode.SwitchingProtocols
        val isBodyless = isBodylessResponse(status, head.method)
        val responseHeaders = callContext.responseHead.headers
        val responseConnTokens = responseHeaders.connectionTokens()

        if (isUpgrade || "close" in responseConnTokens) {
            keepAlive = false
        } else if (!isBodyless) {
            val hasContentLength = responseHeaders.contains(HttpHeaders.ContentLength)
            val hasChunked = responseHeaders[HttpHeaders.TransferEncoding]
                .equals("chunked", ignoreCase = true)
            if (!hasContentLength && !hasChunked) {
                if (handler == null && !callContext.isHeaderCommit) {
                    responseHeaders[HttpHeaders.ContentLength] = "0"
                } else {
                    keepAlive = false
                }
            }
        }

        if (!keepAlive && !isUpgrade && !callContext.isHeaderCommit && "close" !in responseConnTokens) {
            responseHeaders[HttpHeaders.Connection] = "close"
        }

        callContext.responseSink.flush()
        callContext.responseSink.close()

        logger.trace("Response sent", mapOf(
            "status" to callContext.responseHead.statusCode.value,
            "durationMs" to started.elapsedNow().inWholeMilliseconds,
            "keepAlive" to keepAlive,
        ))

        return keepAlive
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

private fun Headers.connectionTokens(): Set<String> {
    return parseConnectionTokens(getAll(HttpHeaders.Connection))
}

private fun HeadersBuilder.connectionTokens(): Set<String> {
    return parseConnectionTokens(getAll(HttpHeaders.Connection))
}

private fun parseConnectionTokens(values: List<String>?): Set<String> {
    if (values.isNullOrEmpty()) return emptySet()
    val tokens = HashSet<String>()
    for (value in values) {
        for (part in value.split(',')) {
            val token = part.trim().lowercase()
            if (token.isNotEmpty()) {
                tokens.add(token)
            }
        }
    }
    return tokens
}
