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
import kotlinx.coroutines.CancellationException
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

        try {
            scope.block()
        } catch (cancellation: CancellationException) {
        } catch (t: Throwable) {
            println("exception happened $t")
            scope.callContext.respond(HttpStatusCode.InternalServerError, t.toString())
        } finally {
            scope.callContext.requestBody?.close()
        }

        if (!scope.callContext.requestHandled) {
            scope.callContext.respond(HttpStatusCode.NotFound)
        }
        scope.callContext.buildResponse()
    }
}

inline fun RouteScope.inject(noinline interceptor: CallInterceptor, block: () -> Unit) {
    interceptors.addLast(interceptor)
    block()
    interceptors.removeLast()
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
    crossinline block: suspend CallContext.() -> Unit
) {
    if (callContext.requestHandled) return

    val canHandle = this.method == method && this.uri == uri
    if (!canHandle) return

    val handler: suspend CallContext.() -> Unit = {
        block()
    }
    foldCallInterceptor(interceptors, handler).invoke(callContext)
}

typealias CallInterceptor = suspend CallContext.(proceed: suspend CallContext.() -> Unit) -> Unit

@PublishedApi
internal fun foldCallInterceptor(
    interceptors: List<CallInterceptor>,
    handler: suspend CallContext.() -> Unit
): suspend CallContext.() -> Unit {
    return interceptors.foldRight(handler) { interceptor, next ->
        {
            interceptor(next)
        }
    }
}

class RouteScope(request: HttpRequest) {
    @PublishedApi
    internal val interceptors: ArrayDeque<CallInterceptor> = ArrayDeque()

    @PublishedApi
    internal val method = request.head.method

    @PublishedApi
    internal val uri = request.head.uri

    @PublishedApi
    internal val callContext = CallContext(request)
}

class CallContext(private val request: HttpRequest) {
    val requestHead = request.head
    var requestBody = request.body?.buffered()

    @PublishedApi
    internal var requestHandled: Boolean = false

    @PublishedApi
    internal val responseHeadBuilder = HttpResponseHead.Builder()

    @PublishedApi
    internal var responseBodySource: LimitedSource? = null

    @PublishedApi
    internal fun buildResponse(): HttpResponse {
        if (responseHeadBuilder.version == null) {
            responseHeadBuilder.version = request.head.version
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

        val read = source.readAtMostTo(
            sink = buffer,
            byteCount = minOf(8192L, source.bytesRemaining)
        )

        if (read == -1L) break

        buffer.skip(read)
    }
}
