package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kio.async.Poller
import kio.async.buffered
import kio.async.openPipe
import kio.async.readString
import kio.async.runPollEventLoop
import kio.network.AsyncRawConnection
import kio.network.buffered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

abstract class Http2ConnectionTest {
    abstract val poller: Poller.Factory

    @Test
    fun canAckPing() = withHttp2Test {
        peerSendPing(4, 23)
        responseFrame {
            assertIs<Frame.PingAck>(this)
            assertEquals(4, payload1)
            assertEquals(23, payload2)
        }
    }

    @Test
    fun canSendAckSettingFrameWhenReceiveSettingFrame() = withHttp2Test {
        peerSendSetting(Settings())
        responseFrame { assertIs<Frame.SettingsAck>(this) }
    }

    @Test
    fun streamCanCreatedWhenReceiveHeaderFrame() = withHttp2Test {
        peerSendSetting(Settings())
        responseFrame {}

        peerSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(1, stream.streamId)
        completer.complete(Unit)
    }

    @Test
    fun streamSourceExhaustedWhenNoRequestBody() = withHttp2Test {
        peerSendSetting(Settings())
        responseFrame {}

        peerSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        assertTrue(stream.source.buffered().exhausted())
        completer.complete(Unit)
    }

    @Test
    fun changeInitialWindowSizeSettings() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        peerSendSetting(initial)
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        peerSendSetting(shouldntImpactConnection)
        responseFrame { assertIs<Frame.SettingsAck>(this) }
        assertEquals(3368, serverConn.peerSettings.initialWindowSize)

        peerSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        // New Stream is has the most recent initial window size.
        assertEquals(3368, stream.windowSizeCounter.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun streamMaximumWriteSizeChangedAfterCreated() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        peerSendSetting(initial)
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        // create stream before change.
        peerSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()

        // Change INITIAL_WINDOW_SIZE
        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        peerSendSetting(shouldntImpactConnection)
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        // new size applied.
        assertEquals(3368, stream.windowSizeCounter.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun peerHttp2ServerZerosCompressionTable() = withHttp2Test {
        val initial = Settings()
        initial[Settings.HEADER_TABLE_SIZE] = 0
        peerSendSetting(initial)
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(0, serverConn.peerSettings.headerTableSize)
        assertEquals(0, serverConn.hpackWriter.dynamicTableByteCount)
        assertEquals(0, serverConn.hpackWriter.headerTableSizeSetting)
    }

    @Test
    fun peerIncreasesMaxFrameSize() = withHttp2Test {
        val newMaxFrameSize = 0x4001
        val settings = Settings()
        settings[Settings.MAX_FRAME_SIZE] = newMaxFrameSize
        peerSendSetting(settings)
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(newMaxFrameSize, serverConn.peerSettings.getMaxFrameSize(-1))
        assertEquals(newMaxFrameSize, serverConn.maxFrameSize)
    }

    @Test
    fun requestBodyReadThrowsWhenStreamIsResetByGoAway() = withHttp2Test {
        peerSendSetting(Settings())
        responseFrame { assertIs<Frame.SettingsAck>(this) }
        peerSendHeader(true, 3, listOf(Header("a", "value")))
        val (stream1, completer1) = assertStreamCreated()

        peerSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream2, completer2) = assertStreamCreated()

        // trigger the stream1 read.
        stream2.scope.launch {
            stream2.buffered().source.readString()
        }

        // cancel stream2.
        peerSendGoAway(3, ErrorCode.PROTOCOL_ERROR, ByteArray(0))
        // make sure GoAway handled.
        awaitNextPeerFrame()

        assertTrue(stream1.scope.isActive)
        assertFalse(stream2.scope.isActive)

        assertEquals(1, serverConn.streams.size)
        completer1.complete(Unit)
    }

    @Test
    fun suspendWriteFrameWhenWindowSizeEqualToZero() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 50
        peerSendSetting(initial)
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(50, stream.windowSizeCounter.writeBytesMaximum)

        // write data frame
        sendDataFrame(stream, false, 10)
        responseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        assertEquals(10, serverConn.windowSizeCounter.writeBytesTotal)
        assertEquals(10, stream.windowSizeCounter.writeBytesTotal)
        assertEquals(40, stream.windowSizeCounter.remainWindowSize)

        sendDataFrame(stream, false, 40)
        responseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        assertEquals(50, serverConn.windowSizeCounter.writeBytesTotal)
        assertEquals(50, stream.windowSizeCounter.writeBytesTotal)
        assertEquals(0, stream.windowSizeCounter.remainWindowSize)

        // no more window size, write call will be suspend.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5.milliseconds) {
                sendDataFrame(stream, false, 10)
            }
        }

        val writeJob = stream.scope.launch {
            sendDataFrame(stream, false, 10)
        }

        // increase window size for stream[5]
        peerSendWindowUpdate(5, 10)
        awaitNextPeerFrame()

        // write job will complete.
        withTimeout(5.milliseconds) {
            writeJob.join()
        }

        responseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        completer.complete(Unit)
    }

    @Test
    fun concurrentWriteDataGuardByWindowSize() = withHttp2Test {
        peerSendSetting(Settings())
        responseFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 3, listOf(Header("b", "value")))
        val (stream1, completer1) = assertStreamCreated()

        peerSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream2, completer2) = assertStreamCreated()

        // consume the connection level window size.
        while (serverConn.windowSizeCounter.remainWindowSize > 0) {
            val writeSize = minOf(1024, serverConn.windowSizeCounter.remainWindowSize)
            sendDataFrame(stream1, false, writeSize)
            responseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }
        }

        // Window size of connection and stream1 is 0
        assertEquals(0, serverConn.windowSizeCounter.remainWindowSize)
        assertEquals(0, stream1.windowSizeCounter.remainWindowSize)

        val job1 = stream1.scope.launch {
            sendDataFrame(stream1, false, 10)
        }

        val job2 = stream1.scope.launch {
            sendDataFrame(stream2, false, 10)
        }

        // increase window size for connection
        peerSendWindowUpdate(0, 20)
        awaitNextPeerFrame()

        // write data job of stream 2 will finish
        withTimeout(5.milliseconds) {
            job2.join()
        }

        assertTrue(job1.isActive)

        // increase window size for stream[3]
        peerSendWindowUpdate(3, 10)
        awaitNextPeerFrame()

        // write data job of stream 1 will finish
        withTimeout(5.milliseconds) {
            job1.join()
        }

        completer1.complete(Unit)
        completer2.complete(Unit)
    }

    private fun withHttp2Test(block: suspend Http2TestScope.() -> Unit) =
        runPollEventLoop(poller) {
            supervisorScope {
                val mock = MockHttpClientServerConnection(this)
                val scop = Http2TestScope(mock, this.coroutineContext)

                val job = launch {
                    mock.http2Conn.frameReadLoop(
                        onFrame = { scop.onFrame() }
                    )
                }

                block(scop)
                job.cancel()
            }
        }
}

private class Http2TestScope(
    private val mockConn: MockHttpClientServerConnection,
    override val coroutineContext: CoroutineContext
) : CoroutineScope {
    val serverConn: Http2Connection = mockConn.http2Conn
    val clientConn: Http2Connection = mockConn.clientConn

    suspend fun peerSendPing(payload1: Int, payload2: Int) = with(mockConn.clientConn) {
        clientConn.socketConn.sink.writePing(false, payload1, payload2)
        clientConn.socketConn.sink.flush()
    }

    suspend fun peerSendSetting(
        settings: Settings
    ) = with(clientConn) {
        clientConn.socketConn.sink.writeSetting(settings)
        clientConn.socketConn.sink.flush()
    }

    suspend fun peerSendGoAway(
        lastGoodStreamId: Int,
        errorCode: ErrorCode,
        debugData: ByteArray,
    ) = with(clientConn) {
        clientConn.socketConn.sink.writeGoAway(lastGoodStreamId, errorCode, debugData)
        clientConn.socketConn.sink.flush()
    }

    suspend fun peerSendWindowUpdate(
        streamId: Int,
        windowSizeIncrement: Long,
    ) = with(clientConn) {
        clientConn.socketConn.sink.writeWindowUpdate(streamId, windowSizeIncrement)
        clientConn.socketConn.sink.flush()
    }

    suspend fun sendDataFrame(
        stream: Http2Stream,
        outFinished: Boolean,
        byteCount: Int,
    ) = context(serverConn, stream.windowSizeCounter) {
        val buffer = Buffer().apply { write(ByteArray(byteCount)) }
        serverConn.socketConn.sink.writeData(stream.streamId, outFinished, buffer, byteCount.toLong())
        serverConn.socketConn.sink.flush()
    }

    suspend fun peerSendHeader(
        outFinished: Boolean,
        streamId: Int,
        headerBlock: List<Header>,
    ) = with(clientConn) {
        clientConn.socketConn.sink.writeHeaders(
            outFinished,
            streamId,
            headerBlock,
        )
        clientConn.socketConn.sink.flush()
    }

    suspend fun assertStreamCreated(): Pair<Http2Stream, CompletableDeferred<Unit>> {
        return withTimeout(100.milliseconds) {
            assertNotNull(mockConn.createdHttp2Stream.receive())
        }
    }

    private val continuation = ContinuationSource(mockConn.serverWriteBackSource)
    private val hpackReader: Hpack.Reader =
        Hpack.Reader(
            source = continuation,
            headerTableSizeSetting = 4096,
        )

    suspend fun responseFrame(block: suspend Frame.(AsyncSource) -> Unit) {
        val frame = context(hpackReader, continuation) {
            withTimeout(100.milliseconds) {
                mockConn.serverWriteBackSource.nextFrame()
            }
        }
        block(frame, mockConn.serverWriteBackSource)
    }

    private var nextClientFrameCont: Continuation<Unit>? = null

    suspend fun awaitNextPeerFrame(): Unit = withTimeout(10.milliseconds) {
        suspendCoroutine {
            if (nextClientFrameCont != null) error("something err")
            nextClientFrameCont = it
        }
    }

    fun onFrame() {
        nextClientFrameCont?.resume(Unit)
        nextClientFrameCont = null
    }
}

private class MockHttpClientServerConnection(
    testScope: CoroutineScope
) {
    private val clientPipeConn = openPipe()
    private val serverPipeConn = openPipe()

    val serverWriteBackSource = serverPipeConn.first

    val serverConnection = object : AsyncRawConnection {
        override val source: AsyncRawSource = clientPipeConn.first

        override val sink: AsyncRawSink = serverPipeConn.second

        override suspend fun close() {
            TODO("Not yet implemented")
        }
    }

    val clientConnection = object : AsyncRawConnection {
        override val source: AsyncRawSource = serverPipeConn.first

        override val sink: AsyncRawSink = clientPipeConn.second

        override suspend fun close() {
            TODO("Not yet implemented")
        }
    }

    var createdHttp2Stream: Channel<Pair<Http2Stream, CompletableDeferred<Unit>>> = Channel()

    val http2Conn = Http2Connection(
        serverConnection.buffered(),
        testScope,
    )

    val clientConn = Http2Connection(
        clientConnection.buffered(),
        testScope
    )

    init {
        http2Conn.handleStreamConnection = {
            val handle = CompletableDeferred<Unit>()
            createdHttp2Stream.send(it to handle)
            handle.await()
            println("stream handle of $it finished")
        }
    }
}