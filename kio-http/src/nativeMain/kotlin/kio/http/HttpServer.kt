package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kio.async.LimitedSource
import kio.async.limited
import kio.network.AsyncConnection
import kio.network.AsyncRawConnection
import kio.network.ServerSocket
import kio.network.buffered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer

suspend inline fun CoroutineScope.httpServer(
    serverSocket: ServerSocket,
    noinline connectionWrapper: AsyncRawConnection.() -> AsyncConnection = { buffered() },
    crossinline block: RouteScope.() -> Unit
) {
    httpServer(serverSocket, connectionWrapper) { request ->
        val scope = RouteScope(request)
        scope.block()
        scope.responseScope.response ?: error("No route to handle request ${request.head}")
    }
}

inline fun RouteScope.get(uri: String, block: ResponseScope.() -> Unit) {
    handleRequest(HttpMethod.Get, uri, block)
}

inline fun RouteScope.post(uri: String, block: ResponseScope.() -> Unit) {
    handleRequest(HttpMethod.Post, uri, block)
}

@PublishedApi
internal suspend fun CoroutineScope.httpServer(
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection,
    handler: (HttpRequest) -> HttpResponse
) {
    while (true) {
        val raw = serverSocket.accept()
        val conn = raw.connectionWrapper()

        launch {
            handleHttpConnection(conn, handler)
        }
    }
}

@PublishedApi
internal inline fun RouteScope.handleRequest(
    method: HttpMethod,
    uri: String,
    block: ResponseScope.() -> Unit
) {
    if (requestHandled) return

    val canHandle = this.method == method && this.uri == uri
    if (!canHandle) return

    responseScope.apply {
        block()
        buildResponse()
    }
}

class RouteScope(val request: HttpRequest) {
    @PublishedApi
    internal val method = request.head.method

    @PublishedApi
    internal val uri = request.head.uri

    @PublishedApi
    internal val responseScope = ResponseScope(this)

    @PublishedApi
    internal val requestHandled: Boolean
        get() = responseScope.response != null
}

class ResponseScope(private val parent: RouteScope) {
    val request = parent.request

    @PublishedApi
    internal val responseHeadBuilder = HttpResponseHead.Builder()

    @PublishedApi
    internal var responseBodySource: LimitedSource? = null

    @PublishedApi
    internal fun buildResponse() {
        if (responseHeadBuilder.version == null) {
            responseHeadBuilder.version = request.head.version
        }

        response = HttpResponse(
            responseHeadBuilder.build(),
            responseBodySource
        )
    }

    @PublishedApi
    internal var response: HttpResponse? = null
}

private suspend fun handleHttpConnection(
    conn: AsyncConnection,
    handler: (HttpRequest) -> HttpResponse
) {
    while (true) {
        val requestHead = conn.source.parseRequestHead()
// TODO: handle exception on each request
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
