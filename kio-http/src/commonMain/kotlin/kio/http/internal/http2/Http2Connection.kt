package kio.http.internal.http2

import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import kio.http.RouteScope
import kio.http.handleHttp2Request
import kio.http.internal.HttpRequestHead
import kio.http.internal.http2.Http2.FLAG_ACK
import kio.http.internal.http2.Http2.TYPE_SETTINGS
import kio.network.AsyncConnection
import kio.network.buffered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.bytestring.decodeToString

internal suspend fun RouteScope.handleHttp2Connection(conn: AsyncConnection) = coroutineScope {
    conn.source.readPreface()
    conn.sink.writeSetting(Settings())
    conn.sink.flush()

    val http2Connection = Http2Connection(
        socketConn = conn,
        scope = this@coroutineScope,
        handleStreamConnection = { stream ->
            val requestHead = stream.requestHead
            handleHttp2Request(stream.streamId, requestHead, stream.buffered())
        }
    )
}

internal class Http2Connection(
    val socketConn: AsyncConnection,
    val scope: CoroutineScope,
    val handleStreamConnection: suspend Http2Connection.(Http2Stream) -> Unit
) {
    /**
     * Serializes writes to the connection sink.
     *
     * Each HTTP/2 frame must be written atomically.
     */
    val writerMutex = Mutex()

    private val streams = mutableMapOf<Int, Http2Stream>()

    private val connectionHandleScope = scope + SupervisorJob()

    /**
     * Scope for connection-level control frame writers, such as PING,
     * SETTINGS ACK, WINDOW_UPDATE, and GOAWAY.
     */
    private val controlFrameWriterScope = scope + Job()

    val hpackBuffer = Buffer()
    val hpackWriter: Hpack.Writer = Hpack.Writer(out = hpackBuffer)

    init {
        scope.launch { frameReadLoop() }
            .invokeOnCompletion { connectionHandleScope.cancel() }
    }

    fun openStream(streamId: Int, isSourceFinished: Boolean, headers: HttpRequestHead) {
        val stream = Http2Stream(streamId, headers, isSourceFinished, writerMutex, socketConn.sink)
        streams[streamId] = stream
        val job = connectionHandleScope.launch {
            handleStreamConnection(stream)
        }.invokeOnCompletion {
// TODO: remove stream from map
        }
    }

    fun getStream(streamId: Int): Http2Stream? {
        return streams[streamId]
    }

    fun applyAndAckSettings(settings: Settings) {
        controlFrameWriterScope.launch {
            // TODO: apply setting.

            writerMutex.withLock {
                socketConn.sink.frameHeader(
                    streamId = 0,
                    length = 0,
                    type = TYPE_SETTINGS,
                    flags = FLAG_ACK,
                )
            }
            socketConn.sink.flush()
        }
    }
}

context(http2Connection: Http2Connection)
private suspend fun frameReadLoop() {
    val source = http2Connection.socketConn.source
    val continuation = ContinuationSource(source)
    val hpackReader: Hpack.Reader =
        Hpack.Reader(
            source = continuation,
            headerTableSizeSetting = 4096,
        )
    context(hpackReader, continuation) {
        while (true) {
            val frame = source.nextFrame()
            when (frame) {
                is Frame.Headers -> {
                    http2Connection.openStream(
                        frame.streamId,
                        frame.inFinished,
                        frame.headerBlock.toHttpRequestHead(),
                    )
                }

                is Frame.Data -> {
                    val dataStream = http2Connection.getStream(frame.streamId)
                    if (dataStream == null) {
                        source.skip(frame.length)
                        return
                    }
                    dataStream.receiveData(source, frame.length, frame.inFinished)
                }
                is Frame.Setting -> {
                    http2Connection.applyAndAckSettings(frame.settings)
                }
                Frame.AckSettings -> {}
                is Frame.WindowUpdate -> {}
            }
        }
    }
}

private fun List<Header>.toHttpRequestHead(): HttpRequestHead {
    var method: HttpMethod? = null
    var uri: String? = null

    val headersBuilder = HeadersBuilder()
    for ((name, value) in this) {
        when (name) {
            Header.TARGET_METHOD -> method = HttpMethod.parse(value.decodeToString())
            Header.TARGET_PATH -> uri = value.decodeToString()
            Header.TARGET_SCHEME -> {}
            Header.TARGET_AUTHORITY -> {}
            else -> headersBuilder.set(name.decodeToString(), value.decodeToString())
        }
    }

    return HttpRequestHead(
        method ?: error("no http method"),
        uri ?: error("no uri"),
        version = HttpProtocolVersion.HTTP_2_0,
        headers = headersBuilder.build()
    )
}
