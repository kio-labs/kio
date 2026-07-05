package kio.http.internal.http2

import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import kio.async.io.AsyncConnection
import kio.http.RouteScope
import kio.http.handleHttp2Request
import kio.http.internal.HttpRequestHead
import kio.http.internal.HttpResponseHead
import kio.http.internal.http2.Http2.FLAG_ACK
import kio.http.internal.http2.Http2.INITIAL_MAX_FRAME_SIZE
import kio.http.internal.http2.Http2.TYPE_SETTINGS
import kio.http.internal.http2.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.isNotEmpty

class StreamResetCancellationException(
    val errorCode: ErrorCode,
) : CancellationException("stream was reset: $errorCode")

internal suspend fun RouteScope.http2Connection(conn: AsyncConnection) {
    http2Connection(conn) { conn, stream ->
        // handle http2 stream in a launched coroutine.
        with(conn) {
            handleHttp2Request(stream)
        }
    }
}

internal suspend fun http2Connection(
    conn: AsyncConnection,
    handleHttp2Request: suspend CoroutineScope.(Http2Connection, Http2Stream) -> Unit
) {
    // Uncaught exceptions from stream coroutines are reported here.
    // Normal stream failures should be handled inside each stream coroutine.
    // This handler is only a last-resort logger.
    val streamExceptionHandler = CoroutineExceptionHandler { context, t ->
        println("streamExceptionHandler $t")
    }
    withContext(streamExceptionHandler) {
        supervisorScope {
            val http2Connection = Http2Connection(
                socketConn = conn,
                connectionScope = this,
            ).also { connection ->
                connection.handleStreamConnection = { stream ->
                    handleHttp2Request.invoke(this, connection, stream)
                }
            }
            http2Connection.doInitSetting()
            http2Connection.frameReadLoop()
            println("http2Connection.frameReadLoop() finished")
        }
    }
}

internal class Http2Connection constructor(
    val socketConn: AsyncConnection,
    private val connectionScope: CoroutineScope,
) {
    lateinit var handleStreamConnection: suspend CoroutineScope.(Http2Stream) -> Unit

    /** Settings we communicate to the peer. */
    val http2Settings = Settings()

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

    /** The bytes consumed and acknowledged by the application. */
    val readBytes: WindowCounter = WindowCounter(streamId = 0)

    val maxFrameSize: Int
        get() = peerSettings.getMaxFrameSize(INITIAL_MAX_FRAME_SIZE)

    val windowSizeCounter = WindowSizeCounter(peerSettings.initialWindowSize.toLong())

    val hpackWriter: Hpack.Writer = Hpack.Writer()

    val streams = mutableMapOf<Int, Http2Stream>()

    // notify all observers when received window update event
    private val windowUpdateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var isShutdown = false

    suspend fun doInitSetting() {
        socketConn.source.readPreface()

        socketConn.sink.writeSetting(http2Settings)
        val windowSize = http2Settings.initialWindowSize
        if (windowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
            socketConn.sink.writeWindowUpdate(0, (windowSize - DEFAULT_INITIAL_WINDOW_SIZE).toLong())
        }

        socketConn.sink.flush()
    }

    fun receiveHeader(streamId: Int, isSourceFinished: Boolean, headers: HttpRequestHead) {
        if (isShutdown) return

        when (val stream = streams[streamId]) {
            null -> {}
            else -> {
                // receive trailer.
                check(isSourceFinished) { "receive trailer but the frame is not marked finished." }
                stream.trailers = headers.headers

                stream.sourceFinished()
                return
            }
        }

        val stream = Http2Stream(
            streamId = streamId,
            requestHead = headers,
            sourceFinished = isSourceFinished,
            http2Conn = this
        )

        streams[streamId] = stream
        println("Stream[$streamId] created.")

        connectionScope.launch {
            handleStreamConnection(stream)
        }.also { job ->
            stream.scope = job as CoroutineScope
        }.invokeOnCompletion {
            val stream = streams.remove(streamId)
                ?: error("try to remove stream but Stream[$streamId] not exist.")
            println("Stream[$streamId] closed. exception=$it")
        }
    }

    suspend fun awaitWindowUpdateEvent() {
        windowUpdateEvents.first()
    }

    fun applyAndAckSettings(settings: Settings) {
        connectionScope.launch {
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
                windowUpdateEvents.tryEmit(Unit)
            }

            // apply headerTableSize setting to hpackWriter.
            if (peerSettings.headerTableSize != -1) {
                hpackWriter.resizeHeaderTable(peerSettings.headerTableSize)
            }

            writeFrameNonCancellable {
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

    fun sendWindowUpdate(
        streamId: Int,
        windowSizeIncrement: Long,
    ) {
        connectionScope.launch {
            socketConn.sink.writeWindowUpdate(streamId, windowSizeIncrement)
            socketConn.sink.flush()
        }
    }

    fun ackPing(payload1: Int, payload2: Int) {
        connectionScope.launch {
            socketConn.sink.writePing(true, payload1, payload2)
            socketConn.sink.flush()
        }
    }

    fun receiveGoAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {
        if (debugData.isNotEmpty()) {
            println("receive GoAway from peer: ${debugData.decodeToString()}")
        }

        streams.forEach { (streamId, stream) ->
            if (streamId > lastGoodStreamId) {
                stream.scope.cancel(StreamResetCancellationException(errorCode))
            }
        }

        // mark this connection is shut down, which means it will not accept new streams.
        isShutdown = true
    }

    suspend fun receiveData(
        streamId: Int,
        length: Long,
        inFinished: Boolean
    ) {
        val dataStream = streams[streamId]
        val source = socketConn.source
        if (dataStream == null) {
            source.skip(length)
        } else {
            dataStream.receiveData(source, length, inFinished)
        }

        readBytes.update(total = length)
        val readBytesToAcknowledge = readBytes.unacknowledged
        if (readBytesToAcknowledge >= http2Settings.initialWindowSize / 2) {
            sendWindowUpdate(0, readBytesToAcknowledge)
            readBytes.update(acknowledged = readBytesToAcknowledge)
        }
    }

    fun receiveWindowUpdate(streamId: Int, windowSizeIncrement: Long) {
        if (streamId == 0) {
            windowSizeCounter.increaseWindowSize(windowSizeIncrement)
        } else {
            streams[streamId]?.addBytesToWriteWindow(windowSizeIncrement)
        }
        windowUpdateEvents.tryEmit(Unit)
    }

    fun receiveRstStream(streamId: Int, errorCode: ErrorCode) {
        streams[streamId]?.scope?.cancel(StreamResetCancellationException(errorCode))
    }

    companion object {
        val DEFAULT_SETTINGS =
            Settings().apply {
                set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
                set(Settings.MAX_FRAME_SIZE, INITIAL_MAX_FRAME_SIZE)
            }
    }
}

internal class WindowSizeCounter(
    initialWindowSize: Long
) {
    /** The total number of bytes produced by the application. */
    var writeBytesTotal = 0L
        private set

    /** The total number of bytes permitted to be produced according to `WINDOW_UPDATE` frames. */
    var writeBytesMaximum: Long = initialWindowSize
        private set

    val remainWindowSize: Int
        get() = (writeBytesMaximum - writeBytesTotal).coerceAtLeast(0).toInt()

    fun increaseWindowSize(size: Long) {
        writeBytesMaximum += size
    }

    fun onWrite(size: Int) {
        writeBytesTotal += size
    }
}

internal suspend inline fun Http2Connection.writeFrameNonCancellable(crossinline block: suspend () -> Unit) =
    writerMutex.withLock {
        withContext(NonCancellable) {
            block()
        }
    }

internal suspend fun Http2Connection.frameReadLoop(onFrame: () -> Unit = {}) {
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
            when (val frame = source.nextFrame()) {
                is Frame.Headers -> {
                    http2Connection.receiveHeader(
                        frame.streamId,
                        frame.inFinished,
                        parseHttpRequestHead(frame.headerBlock),
                    )
                }

                is Frame.Data -> {
                    http2Connection.receiveData(frame.streamId, frame.length, frame.inFinished)
                }

                is Frame.Setting -> {
                    http2Connection.applyAndAckSettings(frame.settings)
                }

                is Frame.WindowUpdate -> {
                    http2Connection.receiveWindowUpdate(frame.streamId, frame.windowSizeIncrement)
                }

                is Frame.Ping -> {
                    http2Connection.ackPing(frame.payload1, frame.payload2)
                }

                Frame.SettingsAck -> {}
                is Frame.PingAck -> {}
                is Frame.GoAway -> {
                    http2Connection.receiveGoAway(
                        frame.lastGoodStreamId,
                        frame.errorCode,
                        frame.debugData
                    )
                }

                is Frame.RstStream -> http2Connection.receiveRstStream(
                    frame.streamId,
                    frame.errorCode
                )
            }

            onFrame()
        }
    }
}

internal fun parseHttpRequestHead(headers: List<Header>): HttpRequestHead {
    var method: HttpMethod? = null
    var uri: String? = null

    val headersBuilder = HeadersBuilder()
    for ((name, value) in headers) {
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

internal fun parseHttpResponseHead(headers: List<Header>): HttpResponseHead {
    val builder = HttpResponseHead.Builder()
    for ((name, value) in headers) {
        when (name) {
            Header.RESPONSE_STATUS -> builder.statusCode = HttpStatusCode.fromValue(
                value.decodeToString().toIntOrNull()
                    ?: throw IOException("Not a valid status ${value.decodeToString()}")
            )

            else -> builder.headers.set(name.decodeToString(), value.decodeToString())
        }
    }

    return builder.build()
}
