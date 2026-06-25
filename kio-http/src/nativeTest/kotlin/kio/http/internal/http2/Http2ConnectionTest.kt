package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.buffered
import kio.async.openPipe
import kio.async.poller.poll.PosixPoll
import kio.async.readString
import kio.async.runPollEventLoop
import kio.network.AsyncRawConnection
import kio.network.buffered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class Http2ConnectionTest {
    @Test
    fun serverCanAckPing() = withHttp2Test {
        clientSendPing(4, 23)
        serverSendFrame {
            assertIs<Frame.PingAck>(this)
            assertEquals(4, payload1)
            assertEquals(23, payload2)
        }
    }

    @Test
    fun serverCanSendAckSettingFrameWhenReceiveSettingFrame() = withHttp2Test {
        clientSendSetting(Settings())
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }
    }

    @Test
    fun streamCanCreatedWhenReceiveHeaderFrame() = withHttp2Test {
        clientSendSetting(Settings())
        serverSendFrame {}

        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(1, stream.streamId)
        completer.complete(Unit)
    }

    @Test
    fun streamSourceExhaustedWhenNoRequestBody() = withHttp2Test {
        clientSendSetting(Settings())
        serverSendFrame {}

        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        assertTrue(stream.source.buffered().exhausted())
        completer.complete(Unit)
    }

    @Test
    fun changeInitialWindowSizeSettings() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        clientSendSetting(initial)
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }

        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        clientSendSetting(shouldntImpactConnection)
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }
        assertEquals(3368, conn.peerSettings.initialWindowSize)

        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        // New Stream is has the most recent initial window size.
        assertEquals(3368, stream.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun streamMaximumWriteSizeChangedAfterCreated() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        clientSendSetting(initial)
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }

        // create stream before change.
        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()

        // Change INITIAL_WINDOW_SIZE
        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        clientSendSetting(shouldntImpactConnection)
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }

        // new size applied.
        assertEquals(3368, stream.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun peerHttp2ServerZerosCompressionTable() = withHttp2Test {
        val initial = Settings()
        initial[Settings.HEADER_TABLE_SIZE] = 0
        clientSendSetting(initial)
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(0, conn.peerSettings.headerTableSize)
        assertEquals(0, conn.hpackWriter.dynamicTableByteCount)
        assertEquals(0, conn.hpackWriter.headerTableSizeSetting)
    }

    @Test
    fun peerIncreasesMaxFrameSize() = withHttp2Test {
        val newMaxFrameSize = 0x4001
        val settings = Settings()
        settings[Settings.MAX_FRAME_SIZE] = newMaxFrameSize
        clientSendSetting(settings)
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(newMaxFrameSize, conn.peerSettings.getMaxFrameSize(-1))
        assertEquals(newMaxFrameSize, conn.maxFrameSize)
    }

    @Test
    fun requestBodyReadThrowsWhenStreamIsResetByGoAway() = withHttp2Test {
        clientSendSetting(Settings())
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }
        clientSendHeader(true, 3, listOf(Header("a", "value")))
        val (stream1, completer1) = assertStreamCreated()

        clientSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream2, completer2) = assertStreamCreated()

        // trigger the stream1 read.
        stream2.scope.launch {
            stream2.buffered().source.readString()
        }

        // cancel stream2.
        clientSendGoAway(3, ErrorCode.PROTOCOL_ERROR, ByteArray(0))
        // make sure GoAway handled.
        awaitNextClientFrame()

        assertTrue(stream1.scope.isActive)
        assertFalse(stream2.scope.isActive)

        assertEquals(1, conn.streams.size)
        completer1.complete(Unit)
    }

    @Test
    fun windowUpdateParse() = withHttp2Test {
        clientSendSetting(Settings())
        serverSendFrame { assertIs<Frame.SettingsAck>(this) }

        clientSendWindowUpdate(0, 10)
        awaitNextClientFrame()
    }
}

private fun withHttp2Test(block: suspend Http2TestScope.() -> Unit) =
    runPollEventLoop(PosixPoll) {
        supervisorScope {
            val mock = MockHttp2Connection(this)
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


private class Http2TestScope(
    private val mockConn: MockHttp2Connection,
    override val coroutineContext: CoroutineContext
) : CoroutineScope {
    val conn: Http2Connection = mockConn.http2Conn

    suspend fun clientSendPing(payload1: Int, payload2: Int) = with(conn) {
        mockConn.clientSink.writePing(false, payload1, payload2)
        mockConn.clientSink.flush()
    }

    suspend fun clientSendSetting(
        settings: Settings
    ) = with(conn) {
        mockConn.clientSink.writeSetting(settings)
        mockConn.clientSink.flush()
    }

    suspend fun clientSendGoAway(
        lastGoodStreamId: Int,
        errorCode: ErrorCode,
        debugData: ByteArray,
    ) = with(conn) {
        mockConn.clientSink.writeGoAway(lastGoodStreamId, errorCode, debugData)
        mockConn.clientSink.flush()
    }

    suspend fun clientSendWindowUpdate(
        streamId: Int,
        windowSizeIncrement: Long,
    ) = with(conn) {
        mockConn.clientSink.writeWindowUpdate(streamId, windowSizeIncrement)
        mockConn.clientSink.flush()
    }

    suspend fun clientSendHeader(
        outFinished: Boolean,
        streamId: Int,
        headerBlock: List<Header>,
    ) = with(conn) {
        mockConn.clientSink.writeHeaders(
            outFinished,
            streamId,
            headerBlock,
        )
        mockConn.clientSink.flush()
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

    suspend fun serverSendFrame(block: Frame.() -> Unit) {
        val frame = context(hpackReader, continuation) {
            withTimeout(100.milliseconds) {
                mockConn.serverWriteBackSource.nextFrame()
            }
        }
        block(frame)
    }

    private var nextClientFrameCont: Continuation<Unit>? = null

    suspend fun awaitNextClientFrame(): Unit = suspendCoroutine {
        if (nextClientFrameCont != null) error("something err")
        nextClientFrameCont = it
    }

    fun onFrame() {
        nextClientFrameCont?.resume(Unit)
        nextClientFrameCont = null
    }
}

private class MockHttp2Connection(
    testScope: CoroutineScope
) {
    private val clientPipeConn = openPipe()
    private val serverPipeConn = openPipe()

    val serverWriteBackSource = serverPipeConn.first

    val clientSink: AsyncSink = clientPipeConn.second
    val serverConnection = object : AsyncRawConnection {
        override val source: AsyncRawSource = clientPipeConn.first

        override val sink: AsyncRawSink = serverPipeConn.second

        override suspend fun close() {
            TODO("Not yet implemented")
        }
    }

    var createdHttp2Stream: Channel<Pair<Http2Stream, CompletableDeferred<Unit>>> = Channel()

    val http2Conn = Http2Connection(
        serverConnection.buffered(),
        testScope,
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