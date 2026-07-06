package kio.http

import io.ktor.http.HttpStatusCode
import kio.async.buffered
import kio.http.internal.http2.Http2Connection
import kio.http.internal.http2.Http2ResponseSink
import kio.http.internal.http2.Http2Stream
import kotlinx.coroutines.CancellationException

context(_: Http2Connection)
internal suspend fun RouteScope.handleHttp2Request(
    stream: Http2Stream,
) {
    val handler = getCallHandler(RouteScope.RouteKey(stream.requestHead.method, stream.requestHead.uri))

    doHandleHttp2Request(stream, handler)
}

context(http2Connection: Http2Connection)
internal suspend fun doHandleHttp2Request(
    stream: Http2Stream,
    handler: CallHandler?,
) {
    val callContext = CallContext(
        requestHead = stream.requestHead,
        body = stream.source.buffered(),
        getRequestTrailers = { stream.trailers },
        responseSink = { head, trailer ->
            Http2ResponseSink(
                stream = stream,
                head = head,
                trailer = trailer,
                connection = http2Connection
        // TODO: add header commit callback.
            ).buffered()
        }
    )

    try {
        handler?.invoke(callContext)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        println("exception happened $t")
// TODO: response 500 only if header has not commit yet.
        callContext.respond(HttpStatusCode.InternalServerError, t.toString())
    } finally {
        callContext.requestBody?.close()
    }

    // write response
    callContext.responseSink.flush()
    callContext.responseSink.close()
}
