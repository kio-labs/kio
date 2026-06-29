package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.Poller
import kio.async.buffered
import kio.async.openPipe
import kio.async.readString
import kio.async.runPollEventLoop
import kio.http.internal.http2.Settings.Companion.DEFAULT_INITIAL_WINDOW_SIZE
import kio.network.AsyncRawConnection
import kio.network.buffered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
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
    fun serverCanAckPing() = withHttp2Test {
        clientSendPing(4, 23)
        serverResponseFrame {
            assertIs<Frame.PingAck>(this)
            assertEquals(4, payload1)
            assertEquals(23, payload2)
        }
    }

    @Test
    fun serverCanSendAckSettingFrameWhenReceiveSettingFrame() = withHttp2Test {
        clientSendSetting(Settings())
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }
    }

    @Test
    fun streamCanCreatedWhenReceiveHeaderFrame() = withHttp2Test {
        clientSendSetting(Settings())
        serverResponseFrame {}

        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(1, stream.streamId)
        completer.complete(Unit)
    }

    @Test
    fun streamSourceExhaustedWhenNoRequestBody() = withHttp2Test {
        clientSendSetting(Settings())
        serverResponseFrame {}

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
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        clientSendSetting(shouldntImpactConnection)
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }
        assertEquals(3368, conn.peerSettings.initialWindowSize)

        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        // New Stream is has the most recent initial window size.
        assertEquals(3368, stream.windowSizeCounter.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun streamMaximumWriteSizeChangedAfterCreated() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        clientSendSetting(initial)
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

        // create stream before change.
        clientSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()

        // Change INITIAL_WINDOW_SIZE
        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        clientSendSetting(shouldntImpactConnection)
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

        // new size applied.
        assertEquals(3368, stream.windowSizeCounter.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun peerHttp2ServerZerosCompressionTable() = withHttp2Test {
        val initial = Settings()
        initial[Settings.HEADER_TABLE_SIZE] = 0
        clientSendSetting(initial)
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

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
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(newMaxFrameSize, conn.peerSettings.getMaxFrameSize(-1))
        assertEquals(newMaxFrameSize, conn.maxFrameSize)
    }

    @Test
    fun requestBodyReadThrowsWhenStreamIsResetByGoAway() = withHttp2Test {
        clientSendSetting(Settings())
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }
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
    fun suspendWriteFrameWhenWindowSizeEqualToZero() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 50
        clientSendSetting(initial)
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

        clientSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(50, stream.windowSizeCounter.writeBytesMaximum)

        // write data frame
        serverSendDataFrame(stream, false, 10)
        serverResponseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        assertEquals(10, conn.windowSizeCounter.writeBytesTotal)
        assertEquals(10, stream.windowSizeCounter.writeBytesTotal)
        assertEquals(40, stream.windowSizeCounter.remainWindowSize)

        serverSendDataFrame(stream, false, 40)
        serverResponseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        assertEquals(50, conn.windowSizeCounter.writeBytesTotal)
        assertEquals(50, stream.windowSizeCounter.writeBytesTotal)
        assertEquals(0, stream.windowSizeCounter.remainWindowSize)

        // no more window size, write call will be suspend.
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(5.milliseconds) {
                serverSendDataFrame(stream, false, 10)
            }
        }

        val writeJob = stream.scope.launch {
            serverSendDataFrame(stream, false, 10)
        }

        // increase window size for stream[5]
        clientSendWindowUpdate(5, 10)
        awaitNextClientFrame()

        // write job will complete.
        withTimeout(5.milliseconds) {
            writeJob.join()
        }

        serverResponseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        completer.complete(Unit)
    }

    @Test
    fun concurrentWriteDataGuardByWindowSize() = withHttp2Test {
        clientSendSetting(Settings())
        serverResponseFrame { assertIs<Frame.SettingsAck>(this) }

        clientSendHeader(false, 3, listOf(Header("b", "value")))
        val (stream1, completer1) = assertStreamCreated()

        clientSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream2, completer2) = assertStreamCreated()

        // consume the connection level window size.
        while (conn.windowSizeCounter.remainWindowSize > 0) {
            val writeSize = minOf(1024, conn.windowSizeCounter.remainWindowSize)
            serverSendDataFrame(stream1, false, writeSize)
            serverResponseFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }
        }

        // Window size of connection and stream1 is 0
        assertEquals(0, conn.windowSizeCounter.remainWindowSize)
        assertEquals(0, stream1.windowSizeCounter.remainWindowSize)

        val job1 = stream1.scope.launch {
            serverSendDataFrame(stream1, false, 10)
        }

        val job2 = stream1.scope.launch {
            serverSendDataFrame(stream2, false, 10)
        }

        // increase window size for connection
        clientSendWindowUpdate(0, 20)
        awaitNextClientFrame()

        // write data job of stream 2 will finish
        withTimeout(5.milliseconds) {
            job2.join()
        }

        assertTrue(job1.isActive)

        // increase window size for stream[3]
        clientSendWindowUpdate(3, 10)
        awaitNextClientFrame()

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

    suspend fun serverSendDataFrame(
        stream: Http2Stream,
        outFinished: Boolean,
        byteCount: Int,
    ) = context(conn, stream.windowSizeCounter) {
        val buffer = Buffer().apply { write(ByteArray(byteCount)) }
        val serverSink = mockConn.serverConnection.sink.buffered()
        serverSink.writeData(stream.streamId, outFinished, buffer, byteCount.toLong())
        serverSink.flush()
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

    suspend fun serverResponseFrame(block: suspend Frame.(AsyncSource) -> Unit) {
        val frame = context(hpackReader, continuation) {
            withTimeout(100.milliseconds) {
                mockConn.serverWriteBackSource.nextFrame()
            }
        }
        block(frame, mockConn.serverWriteBackSource)
    }

    private var nextClientFrameCont: Continuation<Unit>? = null

    suspend fun awaitNextClientFrame(): Unit = withTimeout(10.milliseconds) {
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