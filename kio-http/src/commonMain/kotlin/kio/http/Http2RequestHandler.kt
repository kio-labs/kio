package kio.http

import io.ktor.http.HttpStatusCode
import kio.async.buffered
import kio.http.internal.HttpRequestHead
import kio.http.internal.http2.Http2Connection
import kio.http.internal.http2.Http2ResponseSink
import kio.http.internal.http2.Http2Stream
import kio.network.AsyncConnection
import kio.network.buffered
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
    val streamId: Int = stream.streamId
    val head: HttpRequestHead = stream.requestHead
    val streamingConn: AsyncConnection = stream.buffered()

// TODO: assign null if no request body.
    val body = streamingConn.source
    val callContext = CallContext(
        head, body,
        getRequestTrailers = { stream.trailers },
        responseSink = { head, trailer ->
            Http2ResponseSink(
                streamId = streamId,
                head = head,
                trailer = trailer,
                streamingSink = streamingConn.sink,
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
