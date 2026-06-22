package kio.http

import io.ktor.http.HttpStatusCode
import kio.async.buffered
import kio.http.internal.HttpRequestHead
import kio.http.internal.http2.Http2Connection
import kio.http.internal.http2.Http2ResponseSink
import kio.network.AsyncConnection
import kotlinx.coroutines.CancellationException

context(_: Http2Connection)
internal suspend fun RouteScope.handleHttp2Request(
    streamId: Int,
    head: HttpRequestHead,
    streamingConn: AsyncConnection,
) {
    val handler = getCallHandler(RouteScope.RouteKey(head.method, head.uri))

    doHandleHttp2Request(streamId, head, streamingConn, handler)
}

context(http2Connection: Http2Connection)
internal suspend fun doHandleHttp2Request(
    streamId: Int,
    head: HttpRequestHead,
    streamingConn: AsyncConnection,
    handler: CallHandler?,
) {
// TODO: assign null if no request body.
    val body = streamingConn.source
    val callContext = CallContext(
        head, body,
        responseSink = { headBuilder ->
            Http2ResponseSink(
                streamId = streamId,
                head = headBuilder,
                streamingSink = streamingConn.sink,
                socketConnSink = http2Connection.socketConn.sink,
                hpackWriter = http2Connection.hpackWriter,
                writerMutex = http2Connection.writerMutex,
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
