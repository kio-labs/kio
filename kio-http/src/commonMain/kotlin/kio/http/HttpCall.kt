package kio.http

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.Parameters
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered
import kio.async.emptyAsyncRawSource
import kio.async.io.AsyncConnection
import kio.http.internal.HttpRequestHead
import kio.http.internal.HttpResponseHead

typealias CallHandler = suspend CallContext.() -> Unit

fun interface CallInterceptor {
    suspend fun intercept(
        context: CallContext,
        proceed: CallHandler
    )
}

fun Route.get(uri: String = "", block: suspend (CallContext) -> Unit) {
    registerCall(HttpMethod.Get, uri, block)
}

fun Route.post(uri: String = "", block: suspend (CallContext) -> Unit) {
    registerCall(HttpMethod.Post, uri, block)
}

class CallContext internal constructor(
    internal val conn: AsyncConnection,
    requestHead: HttpRequestHead,
    body: AsyncRawSource = emptyAsyncRawSource(),
    parameters: Parameters = Parameters.Empty,
    private val getRequestTrailers: () -> Headers? = { null },
    responseSink: CallContext.(header: HttpResponseHead.Builder, trailer: HeadersBuilder) -> AsyncSink,
) {
    val parameters: Parameters = parameters
    val requestProtocolVersion: HttpProtocolVersion = requestHead.version
    val requestHeaders: Headers = requestHead.headers
    val requestTrailers: Headers?
        get() = getRequestTrailers()

    val requestBody: AsyncSource by lazy {
        _requestBody.buffered()
    }
    private var _requestBody: AsyncRawSource = body

    internal val responseHead = HttpResponseHead.Builder()
    internal val responseTrailer = HeadersBuilder()

    internal var responseSink: AsyncSink = responseSink(responseHead, responseTrailer)
        private set

    internal var isHeaderCommit: Boolean = false

    internal fun wrapResponseSink(block: (AsyncSink) -> AsyncSink) {
        responseSink = block(responseSink)
    }

    internal fun wrapRequestSource(block: (AsyncRawSource) -> AsyncRawSource) {
        _requestBody = block(_requestBody)
    }
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
