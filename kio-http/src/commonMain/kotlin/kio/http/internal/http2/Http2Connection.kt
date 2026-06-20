package kio.http.internal.http2

import kio.http.RouteScope
import kio.http.handleHttpRequest
import kio.http.internal.HttpRequestHead
import kio.network.AsyncConnection
import kio.network.buffered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

internal suspend fun RouteScope.handleHttp2Connection(conn: AsyncConnection) = coroutineScope {
    conn.source.readPreface()

    val http2Connection = Http2Connection(
        conn = conn,
        scope = this,
        handStreamConnection = { stream ->
            val requestHead = stream.requestHead
            handleHttpRequest(requestHead, stream.buffered())
        }
    )
}

private class Http2Connection(
    val conn: AsyncConnection,
    val scope: CoroutineScope,
    val handStreamConnection: suspend (Http2Stream) -> Unit
) {
    private val streams = mutableMapOf<Int, Http2Stream>()

    private val connectionHandleScope = scope + SupervisorJob()

    init {
        scope.launch { frameReadLoop() }
            .invokeOnCompletion { connectionHandleScope.cancel() }
    }

    fun openStream(streamId: Int, inFinished: Boolean, headers: HttpRequestHead) {
        val stream = Http2Stream(streamId, headers, inFinished)
        streams[streamId] = stream
        val job = connectionHandleScope.launch { handStreamConnection(stream) }
    }

    fun getStream(streamId: Int): Http2Stream? {
        return streams[streamId]
    }
}

private suspend fun Http2Connection.frameReadLoop() {
    val source = conn.source
    val continuation = ContinuationSource(source)
    val hpackReader: Hpack.Reader =
        Hpack.Reader(
            source = continuation,
            headerTableSizeSetting = 4096,
        )
    context(hpackReader, continuation) {
        while (true) {
            when (val frame = source.nextFrame()) {
                is Frame.Headers -> {
                    openStream(
                        frame.streamId,
                        frame.inFinished,
                        frame.headerBlock.toHttpRequestHead(),
                    )
                }

                is Frame.Data -> {
                    val dataStream = getStream(frame.streamId)
                    if (dataStream == null) {
                        source.skip(frame.length)
                        return
                    }
                    dataStream.receiveData(source, frame.length, frame.inFinished)
                }

                Frame.AckSettings -> {}
                is Frame.Setting -> {}
                is Frame.WindowUpdate -> {}
            }
        }
    }
}
