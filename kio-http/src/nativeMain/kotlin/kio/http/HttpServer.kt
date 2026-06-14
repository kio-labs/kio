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

suspend fun CoroutineScope.httpServer(
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection = { buffered() },
    block: suspend RouteScope.() -> Unit
) {
    val scope = RouteScope()
    scope.block()

    httpServer(serverSocket, connectionWrapper) { request ->
        val callContext = CallContext(request)
        val handler = scope.getCallHandler(RouteScope.RouteKey(callContext.method, callContext.uri))
        try {
            handler?.invoke(callContext)
        } catch (cancellation: CancellationException) {
        } catch (t: Throwable) {
            println("exception happened $t")
            callContext.respond(HttpStatusCode.InternalServerError, t.toString())
        } finally {
            callContext.requestBody?.close()
        }

        if (!callContext.requestHandled) {
            callContext.respond(HttpStatusCode.NotFound)
        }
        callContext.buildResponse()
    }
}


typealias CallHandler = suspend CallContext.() -> Unit

fun interface CallInterceptor {
    suspend fun intercept(
        context: CallContext,
        proceed: CallHandler
    )
}

fun RouteScope.inject(interceptor: CallInterceptor, block: () -> Unit) {
    interceptors.addLast(interceptor)
    block()
    interceptors.removeLast()
}

fun RouteScope.get(uri: String, block: suspend (CallContext) -> Unit) {
    registerCall(HttpMethod.Get, uri, block)
}

fun RouteScope.post(uri: String, block: suspend (CallContext) -> Unit) {
    registerCall(HttpMethod.Post, uri, block)
}

private fun RouteScope.registerCall(
    method: HttpMethod,
    uri: String,
    block: CallHandler
) {
    val foldedCallHandler = foldCallInterceptor(interceptors, block)

    registerCallHandler(RouteScope.RouteKey(method, uri), foldedCallHandler)
}

private fun foldCallInterceptor(
    interceptors: List<CallInterceptor>,
    handler: CallHandler
): CallHandler {
    return interceptors.foldRight(handler) { interceptor, next ->
        {
            interceptor.intercept(this, next)
        }
    }
}

private suspend fun CoroutineScope.httpServer(
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

class RouteScope {
    data class RouteKey(val method: HttpMethod, val uri: String)

    internal val interceptors: ArrayDeque<CallInterceptor> = ArrayDeque()

    private val registeredCallHandler = mutableMapOf<RouteKey, CallHandler>()

    internal fun registerCallHandler(key: RouteKey, handler: CallHandler) {
        if (registeredCallHandler.contains(key)) error("route ($key) already registered.")
        registeredCallHandler[key] = handler
    }

    internal fun getCallHandler(key: RouteKey): CallHandler? {
        return registeredCallHandler[key]
    }
}

class CallContext(private val request: HttpRequest) {
    val requestHead = request.head
    var requestBody = request.body?.buffered()
        internal set

    internal val method = request.head.method

    internal val uri = request.head.uri

    internal var requestHandled: Boolean = false

    internal val responseHeadBuilder = HttpResponseHead.Builder()

    internal var responseBodySource: LimitedSource? = null

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
