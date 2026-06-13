package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kio.async.LimitedSource
import kio.async.buffered
import kio.async.limited
import kio.network.AsyncConnection
import kio.network.AsyncRawConnection
import kio.network.ServerSocket
import kio.network.buffered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.IOException

suspend inline fun CoroutineScope.httpServer(
    serverSocket: ServerSocket,
    noinline connectionWrapper: AsyncRawConnection.() -> AsyncConnection = { buffered() },
    crossinline block: suspend RouteScope.() -> Unit
) {
    httpServer(serverSocket, connectionWrapper) { request ->
        val scope = RouteScope(request)
        scope.block()

        if (!scope.requestHandled) {
            scope.callContext.respond(HttpStatusCode.NotFound)
        }
        scope.callContext.buildResponse()
    }
}

suspend inline fun RouteScope.get(uri: String, crossinline block: suspend (CallContext) -> Unit) {
    handleRequest(HttpMethod.Get, uri, block)
}

suspend inline fun RouteScope.post(uri: String, crossinline block: suspend (CallContext) -> Unit) {
    handleRequest(HttpMethod.Post, uri, block)
}

@PublishedApi
internal suspend fun CoroutineScope.httpServer(
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection,
    handler: suspend (HttpRequest) -> HttpResponse
) {
    while (true) {
        val raw = serverSocket.accept()
        val conn = raw.connectionWrapper()

        launch {
            try {
                handleHttpConnection(conn, handler)
            } catch (e: IOException) {
                println("exception when try to keep connection alive $e")
            } finally {
                conn.close()
            }
        }
    }
}

@PublishedApi
internal suspend inline fun RouteScope.handleRequest(
    method: HttpMethod,
    uri: String,
    crossinline block: suspend  CallContext.() -> Unit
) {
    if (requestHandled) return

    val canHandle = this.method == method && this.uri == uri
    if (!canHandle) return

    callContext.apply {
        block()
        requestHandled = true
    }
}

class RouteScope(val request: HttpRequest) {
    @PublishedApi
    internal val method = request.head.method

    @PublishedApi
    internal val uri = request.head.uri

    @PublishedApi
    internal val callContext = CallContext(this)

    @PublishedApi
    internal var requestHandled: Boolean = false
}

class CallContext(private val parent: RouteScope) {
    val requestHead = parent.request.head
    val requestBody = parent.request.body?.buffered()

    @PublishedApi
    internal val responseHeadBuilder = HttpResponseHead.Builder()

    @PublishedApi
    internal var responseBodySource: LimitedSource? = null

    @PublishedApi
    internal fun buildResponse(): HttpResponse {
        if (responseHeadBuilder.version == null) {
            responseHeadBuilder.version = parent.request.head.version
        }

        return HttpResponse(
            responseHeadBuilder.build(),
            responseBodySource
        )
    }
}

private suspend fun handleHttpConnection(
    conn: AsyncConnection,
    handler: suspend (HttpRequest) -> HttpResponse
) {
    while (true) {
        val requestHead = conn.source.parseRequestHead()
        val contentLength =
            requestHead.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L

        val requestBodySource = conn.source.limited(contentLength)
        val httpRequest = HttpRequest(
            head = requestHead,
            body = if (contentLength > 0) requestBodySource else null
        )

        val response = handler.invoke(httpRequest)
        requestBodySource.discardRemaining()

        conn.sink.writeResponseHead(response.head)
        response.body?.let { conn.sink.transferFrom(it) }
        conn.sink.flush()
    }
}

private suspend fun LimitedSource.discardRemaining() {
    val source = this
    if (source.exhausted) return

    val buffer = Buffer()

    while (!source.exhausted) {
        buffer.clear()

        val read = source.asyncReadAtMostTo(
            sink = buffer,
            byteCount = minOf(8192L, source.bytesRemaining)
        )

        if (read == -1L) break

        buffer.skip(read)
    }
}
