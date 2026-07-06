package kio.http

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.buffered
import kio.async.writeString
import kio.http.internal.HttpRequestHead
import kio.http.internal.HttpResponseHead
import kotlin.text.equals

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
    requestHead: HttpRequestHead,
    body: AsyncRawSource?,
    private val getRequestTrailers: () -> Headers? = { null },
    responseSink: (header: HttpResponseHead.Builder, trailer: HeadersBuilder) -> AsyncSink,
) {
    val requestHeaders: Headers = requestHead.headers
    val requestTrailers: Headers?
        get() = getRequestTrailers()
    var requestBody = body?.buffered()
        internal set

    internal val responseHead = HttpResponseHead.Builder()
    internal val responseTrailer = HeadersBuilder()

    internal var responseSink: AsyncSink = responseSink(responseHead, responseTrailer)
        private set

    internal fun wrapResponseSink(block: AsyncSink.() -> AsyncSink) {
        responseSink = block(responseSink)
    }
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
    configHeaders: HeadersBuilder.() -> Unit = {},
    configTrailers: HeadersBuilder.() -> Unit = {}
) {
    val charset = contentType?.charset() ?: Charsets.UTF_8
    require(charset == Charsets.UTF_8) {
        "Only support utf8, but get $charset."
    }

    responseHead.apply {
        statusCode = status ?: HttpStatusCode.OK
        headers[HttpHeaders.ContentType] = defaultTextContentType(contentType).toString()
        if (headers.canWriteContentLength()) {
            headers[HttpHeaders.ContentLength] = text.length.toString()
        }
    }
    responseHead.headers.configHeaders()
    responseTrailer.configTrailers()

    responseSink.writeString(text)
}

private fun HeadersBuilder.canWriteContentLength(): Boolean {
    return !this[HttpHeaders.TransferEncoding].equals("chunked", ignoreCase = true)
}

private fun RouteScope.registerCall(
    method: HttpMethod,
    uri: String,
    block: CallHandler
) {
    val foldedCallHandler = foldCallInterceptor(httpCallInterceptors, block)

    registerCallHandler(RouteScope.RouteKey(method, uri), foldedCallHandler)
}

internal fun foldCallInterceptor(
    interceptors: List<CallInterceptor>,
    handler: CallHandler
): CallHandler {
    return interceptors.foldRight(handler) { interceptor, next ->
        {
            interceptor.intercept(this, next)
        }
    }
}

/**
 * Creates a default [ContentType] based on the given [contentType] and current call.
 *
 * If [contentType] is `null`, it tries to fetch an already set "Content-Type" response header.
 * If the header is not available, `text/plain` is used. If [contentType] is specified, it uses it.
 *
 * Additionally, if a content type is `Text` and a charset is not set for a content type,
 * it appends `; charset=UTF-8` to the content type.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.response.defaultTextContentType)
 */
internal fun CallContext.defaultTextContentType(contentType: ContentType?): ContentType {
    val result = when (contentType) {
        null -> {
            val headersContentType = responseHead.headers[HttpHeaders.ContentType]
            headersContentType?.let {
                try {
                    ContentType.parse(headersContentType)
                } catch (_: BadContentTypeFormatException) {
                    null
                }
            } ?: ContentType.Text.Plain
        }

        else -> contentType
    }

    return if (result.charset() == null && result.match(ContentType.Text.Any)) {
        result.withCharset(Charsets.UTF_8)
    } else {
        result
    }
}
