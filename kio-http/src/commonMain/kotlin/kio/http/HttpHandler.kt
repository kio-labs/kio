package kio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kio.async.LimitedSource
import kio.async.buffered
import kio.network.AsyncConnection
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer

typealias CallHandler = suspend CallContext.() -> Unit

fun interface CallInterceptor {
    suspend fun intercept(
        context: CallContext,
        proceed: CallHandler
    )
}

fun RouteScope.inject(interceptor: CallInterceptor, block: () -> Unit) {
    httpCallInterceptors.addLast(interceptor)
    block()
    httpCallInterceptors.removeLast()
}

fun RouteScope.get(uri: String, block: suspend (CallContext) -> Unit) {
    registerCall(HttpMethod.Get, uri, block)
}

fun RouteScope.post(uri: String, block: suspend (CallContext) -> Unit) {
    registerCall(HttpMethod.Post, uri, block)
}

class CallContext(
    val requestHead: HttpRequestHead,
    body: LimitedSource?,
) {
    var requestBody = body?.buffered()
        internal set

    internal val responseBuilder = HttpResponse.Builder()
}

fun CallContext.respond(
    status: HttpStatusCode, message: String = ""
) {
    respondText(status = status, text = message)
}

fun CallContext.respondText(
    text: String,
    contentType: ContentType? = null,
    status: HttpStatusCode? = null,
    configure: HttpResponseHead.Builder.() -> Unit = {}
) {
    responseBuilder.respondText(text, contentType, status, configure)
}

internal suspend fun RouteScope.handleHttpRequest(
    head: HttpRequestHead,
    body: LimitedSource?,
    conn: AsyncConnection
) {
    val callContext = CallContext(head, body)
    val handler = getCallHandler(
        RouteScope.RouteKey(
            callContext.requestHead.method,
            callContext.requestHead.uri
        )
    )

    try {
        handler?.invoke(callContext)
    } catch (cancellation: CancellationException) {
    } catch (t: Throwable) {
        println("exception happened $t")
        callContext.respond(HttpStatusCode.InternalServerError, t.toString())
    } finally {
        callContext.requestBody?.close()
    }

    // discard unread request body source.
    body?.discardRemaining()

    // write response
    val response = callContext.responseBuilder.build()
    response.flushToConnectionSink(conn.sink)
}

private fun RouteScope.registerCall(
    method: HttpMethod,
    uri: String,
    block: CallHandler
) {
    val foldedCallHandler = foldCallInterceptor(httpCallInterceptors, block)

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
