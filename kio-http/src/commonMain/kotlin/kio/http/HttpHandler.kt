package kio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.utils.io.charsets.Charsets
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.buffered
import kio.async.writeString
import kio.http.internal.Drainable
import kio.http.internal.httpResponseSink
import kio.network.AsyncConnection
import kotlinx.coroutines.CancellationException

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

class CallContext internal constructor(
    val requestHead: HttpRequestHead,
    body: AsyncRawSource?,
    connSink: AsyncSink
) {
    var requestBody = body?.buffered()
        internal set

    internal val responseHead = HttpResponseHead.Builder()

    internal val responseSink = connSink.httpResponseSink(responseHead).buffered()
}

suspend fun CallContext.respond(
    status: HttpStatusCode, message: String = ""
) {
    respondText(status = status, text = message)
}

suspend fun CallContext.respondText(
    text: String,
    contentType: ContentType? = null,
    status: HttpStatusCode? = null,
    configure: HttpResponseHead.Builder.() -> Unit = {}
) {
    val charset = contentType?.charset() ?: Charsets.UTF_8
    require(charset == Charsets.UTF_8) {
        "Only support utf8, but get $charset."
    }

    responseHead.apply {
        configure()
        statusCode = status ?: HttpStatusCode.OK
        headers[HttpHeaders.ContentType] = defaultTextContentType(contentType).toString()
        headers[HttpHeaders.ContentLength] = text.length.toString()
    }

    responseSink.writeString(text)
}

internal suspend fun RouteScope.handleHttpRequest(
    head: HttpRequestHead,
    conn: AsyncConnection,
) {
    val handler = getCallHandler(RouteScope.RouteKey(head.method, head.uri))

    doHandleHttpRequest(head, conn, handler)
}

internal suspend fun doHandleHttpRequest(
    head: HttpRequestHead,
    conn: AsyncConnection,
    handler: CallHandler?,
) {
    val contentLength = head.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    val encoding = head.headers[HttpHeaders.TransferEncoding]

    val body = when {
        encoding == "chunked" -> conn.source.chunked()
        contentLength > 0 -> conn.source.limited(contentLength)
        else -> null
    }

    val callContext = CallContext(head, body, conn.sink)

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

    // discard unread request body source.
    (body as? Drainable)?.drain()

    // write response
    callContext.responseSink.flush()
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
