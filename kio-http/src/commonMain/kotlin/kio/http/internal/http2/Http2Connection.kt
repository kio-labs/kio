package kio.http.internal.http2

import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import kio.http.RouteScope
import kio.http.handleHttp2Request
import kio.http.internal.HttpRequestHead
import kio.http.internal.http2.Http2.FLAG_ACK
import kio.http.internal.http2.Http2.INITIAL_MAX_FRAME_SIZE
import kio.http.internal.http2.Http2.TYPE_SETTINGS
import kio.http.internal.http2.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import kio.network.AsyncConnection
import kio.network.buffered
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.decodeToString

internal suspend fun RouteScope.handleHttp2Connection(conn: AsyncConnection) {
    // Uncaught exceptions from stream coroutines are reported here.
    // Normal stream failures should be handled inside each stream coroutine.
    // This handler is only a last-resort logger.
    val streamExceptionHandler = CoroutineExceptionHandler { context, t ->
        println("streamExceptionHandler $t")
    }
    withContext(streamExceptionHandler) {
        runHttp2Connection(conn)
    }
}

private suspend fun RouteScope.runHttp2Connection(conn: AsyncConnection) =
    supervisorScope {
        val http2Connection = Http2Connection(
            socketConn = conn,
            scope = this,
            handleStreamConnection = { stream ->
                // handle http2 stream in a launched coroutine.
                val requestHead = stream.requestHead
                handleHttp2Request(stream.streamId, requestHead, stream.buffered())
            }
        )
        http2Connection.doInitSetting()
        http2Connection.frameReadLoop()
    }

internal class Http2Connection constructor(
    val socketConn: AsyncConnection,
    private val scope: CoroutineScope,
    private val handleStreamConnection: suspend Http2Connection.(Http2Stream) -> Unit
) {
    /**
     * Serializes writes to the connection sink.
     *
     * Each HTTP/2 frame must be written atomically.
     */
    val writerMutex: Mutex = Mutex()
    /**
     * Settings we receive from the peer. Changes to the field are guarded by this. The instance is
     * never mutated once it has been assigned.
     */
    var peerSettings = DEFAULT_SETTINGS
    val maxFrameSize: Int
        get() = peerSettings.getMaxFrameSize(INITIAL_MAX_FRAME_SIZE)

    val hpackWriter: Hpack.Writer = Hpack.Writer()

    private val streams = mutableMapOf<Int, Http2Stream>()

    suspend fun doInitSetting() {
        socketConn.source.readPreface()

        socketConn.sink.writeSetting(Settings())
        socketConn.sink.flush()
    }

    fun openStream(streamId: Int, isSourceFinished: Boolean, headers: HttpRequestHead) {
        val stream = Http2Stream(
            streamId = streamId,
            requestHead = headers,
            sourceFinished = isSourceFinished,
            http2Conn = this
        )
        println("Stream[$streamId] created.")
        streams[streamId] = stream
        scope.launch {
            handleStreamConnection(stream)
        }.invokeOnCompletion {
            val stream = streams.remove(streamId)
                ?: error("try to remove stream but Stream[$streamId] not exist.")
            println("Stream[$streamId] closed. exception=$it")
        }
    }

    fun getStream(streamId: Int): Http2Stream? {
        return streams[streamId]
    }

    fun applyAndAckSettings(settings: Settings) {
        scope.launch {
            val previousPeerSettings = peerSettings
            peerSettings = Settings().apply {
                merge(previousPeerSettings)
                merge(settings)
            }

            // apply initialWindowSize setting to active stream.
            val delta = peerSettings.initialWindowSize - previousPeerSettings.initialWindowSize
            if (delta != 0 && streams.isNotEmpty()) {
                streams.forEach { (id, stream) ->
                    stream.addBytesToWriteWindow(delta.toLong())
                }
            }

            // apply headerTableSize setting to hpackWriter.
            if (peerSettings.headerTableSize != -1) {
                hpackWriter.resizeHeaderTable(peerSettings.headerTableSize)
            }

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

    fun ackPing(payload1: Int, payload2: Int) {
        scope.launch {
            socketConn.sink.writePing(true, payload1, payload2)
            socketConn.sink.flush()
        }
    }

    companion object {
        val DEFAULT_SETTINGS =
            Settings().apply {
                set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
                set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE)
            }
    }
}

internal suspend fun Http2Connection.frameReadLoop() {
    val http2Connection = this
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

                is Frame.WindowUpdate -> {}
                is Frame.Ping -> {
                    http2Connection.ackPing(frame.payload1, frame.payload2)
                }

                Frame.SettingsAck -> {}
                is Frame.PingAck -> {}
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
        method ?: HttpMethod.Get,
        uri ?: "/",
        version = HttpProtocolVersion.HTTP_2_0,
        headers = headersBuilder.build()
    )
}
