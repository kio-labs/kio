package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.Poller
import kio.async.buffered
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

abstract class Http2ConnectionTest {
    abstract val poller: Poller.Factory

    @Test
    fun canAckPing() = withHttp2Test {
        peerSendPing(4, 23)
        takeFrame {
            assertIs<Frame.PingAck>(this)
            assertEquals(4, payload1)
            assertEquals(23, payload2)
        }
    }

    @Test
    fun canSendAckSettingFrameWhenReceiveSettingFrame() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }
    }

    @Test
    fun streamCanCreatedWhenReceiveHeaderFrame() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame {}

        peerSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(1, stream.streamId)
        completer.complete(Unit)
    }

    @Test
    fun streamSourceExhaustedWhenNoRequestBody() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame {}

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
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        peerSendSetting(shouldntImpactConnection)
        takeFrame { assertIs<Frame.SettingsAck>(this) }
        assertEquals(3368, conn.peerSettings.initialWindowSize)

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
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        // create stream before change.
        peerSendHeader(true, 1, listOf(Header("a", "value")))
        val (stream, completer) = assertStreamCreated()

        // Change INITIAL_WINDOW_SIZE
        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        peerSendSetting(shouldntImpactConnection)
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        // new size applied.
        assertEquals(3368, stream.windowSizeCounter.writeBytesMaximum)
        completer.complete(Unit)
    }

    @Test
    fun peerHttp2ServerZerosCompressionTable() = withHttp2Test {
        val initial = Settings()
        initial[Settings.HEADER_TABLE_SIZE] = 0
        peerSendSetting(initial)
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(0, conn.peerSettings.headerTableSize)
        assertEquals(0, conn.hpackWriter.dynamicTableByteCount)
        assertEquals(0, conn.hpackWriter.headerTableSizeSetting)
    }

    @Test
    fun peerIncreasesMaxFrameSize() = withHttp2Test {
        val newMaxFrameSize = 0x4001
        val settings = Settings()
        settings[Settings.MAX_FRAME_SIZE] = newMaxFrameSize
        peerSendSetting(settings)
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        assertEquals(newMaxFrameSize, conn.peerSettings.getMaxFrameSize(-1))
        assertEquals(newMaxFrameSize, conn.maxFrameSize)
    }

    @Test
    fun requestBodyReadThrowsWhenStreamIsResetByGoAway() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }
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

        assertEquals(1, conn.streams.size)
        completer1.complete(Unit)
    }

    @Test
    fun suspendWriteFrameWhenWindowSizeEqualToZero() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 50
        peerSendSetting(initial)
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals(50, stream.windowSizeCounter.writeBytesMaximum)

        // write data frame
        sendDataFrame(stream, false, 10)
        takeFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        assertEquals(10, conn.windowSizeCounter.writeBytesTotal)
        assertEquals(10, stream.windowSizeCounter.writeBytesTotal)
        assertEquals(40, stream.windowSizeCounter.remainWindowSize)

        sendDataFrame(stream, false, 40)
        takeFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        assertEquals(50, conn.windowSizeCounter.writeBytesTotal)
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

        takeFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }

        completer.complete(Unit)
    }

    @Test
    fun concurrentWriteDataGuardByWindowSize() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 3, listOf(Header("b", "value")))
        val (stream1, completer1) = assertStreamCreated()

        peerSendHeader(false, 5, listOf(Header("b", "value")))
        val (stream2, completer2) = assertStreamCreated()

        // consume the connection level window size.
        while (conn.windowSizeCounter.remainWindowSize > 0) {
            val writeSize = minOf(1024, conn.windowSizeCounter.remainWindowSize)
            sendDataFrame(stream1, false, writeSize)
            takeFrame { source -> assertIs<Frame.Data>(this); source.skip(this.length) }
        }

        // Window size of connection and stream1 is 0
        assertEquals(0, conn.windowSizeCounter.remainWindowSize)
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

    @Test
    fun readSendsWindowUpdateHttp2() = withHttp2Test {
        conn.http2Settings[Settings.INITIAL_WINDOW_SIZE] = 100

        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 3, listOf(Header("b", "value")))
        val (stream, completer) = assertStreamCreated()

        repeat(3) {
            peerSendData(3, false, 24)
            peerSendData(3, false, 25)
            peerSendData(3, false, 1)
            stream.source.buffered().readTo(Buffer(), 50)
            takeFrame {
                assertIs<Frame.WindowUpdate>(this)
                assertEquals(0, this.streamId)
                assertEquals(50, this.windowSizeIncrement)
            }
            takeFrame {
                assertIs<Frame.WindowUpdate>(this)
                assertEquals(3, this.streamId)
                assertEquals(50, this.windowSizeIncrement)
            }
        }

        completer.complete(Unit)
    }

    @Test
    fun maxFrameSizeHonored() = withHttp2Test {
        val setting = Settings()
        peerSendSetting(setting)
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 3, listOf(Header("b", "value")))
        val (stream, completer) = assertStreamCreated()

        val sink = stream.sink.buffered()
        val buff = ByteArray(conn.maxFrameSize + 1)
        buff.fill('*'.code.toByte())
        sink.write(buff)
        sink.flush()
        takeFrame {
            assertIs<Frame.Data>(this)
            assertEquals(conn.maxFrameSize, length.toInt())
            it.skip(length)
        }
        takeFrame {
            assertIs<Frame.Data>(this)
            assertEquals(1, length.toInt())
            it.skip(1)
        }
        completer.complete(Unit)
    }

    @Test
    fun peerFinishesStreamWithHeaders() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(true, 3, listOf(Header("b", "value")))
        val (stream, completer) = assertStreamCreated()
        assertEquals("value" ,stream.requestHead.headers["b"])
        assertTrue(stream.source.buffered().exhausted())
        completer.complete(Unit)
    }

    @Test
    fun peerWritesTrailersAndReadsTrailers() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 3, listOf(Header("header", "value")))
        val (stream, completer) = assertStreamCreated()

        peerSendHeader(true, 3, listOf(Header("trailer", "value1")))
        awaitNextPeerFrame()
        assertEquals("value1", stream.trailers!!["trailer"])
        assertTrue(stream.source.buffered().exhausted())
        completer.complete(Unit)
    }

    @Test
    fun rstStream() = withHttp2Test {
        peerSendSetting(Settings())
        takeFrame { assertIs<Frame.SettingsAck>(this) }

        peerSendHeader(false, 3, listOf(Header("header", "value")))
        val (stream, completer) = assertStreamCreated()

        peerSendData(stream.streamId, true, 12)
        awaitNextPeerFrame()
        peerSendRstStream(stream.streamId, ErrorCode.NO_ERROR)
        awaitNextPeerFrame()
    }

    // TODO: server write trailer

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
    val conn: Http2Connection = mockConn.http2Conn
    val peerConn: Http2Connection = mockConn.peerConn

    suspend fun peerSendPing(payload1: Int, payload2: Int) = with(mockConn.peerConn) {
        peerConn.socketConn.sink.writePing(false, payload1, payload2)
        peerConn.socketConn.sink.flush()
    }

    suspend fun peerSendSetting(
        settings: Settings
    ) = with(peerConn) {
        peerConn.socketConn.sink.writeSetting(settings)
        peerConn.socketConn.sink.flush()
    }

    suspend fun peerSendGoAway(
        lastGoodStreamId: Int,
        errorCode: ErrorCode,
        debugData: ByteArray,
    ) = with(peerConn) {
        peerConn.socketConn.sink.writeGoAway(lastGoodStreamId, errorCode, debugData)
        peerConn.socketConn.sink.flush()
    }

    suspend fun peerSendWindowUpdate(
        streamId: Int,
        windowSizeIncrement: Long,
    ) = with(peerConn) {
        peerConn.socketConn.sink.writeWindowUpdate(streamId, windowSizeIncrement)
        peerConn.socketConn.sink.flush()
    }

    suspend fun sendDataFrame(
        stream: Http2Stream,
        outFinished: Boolean,
        byteCount: Int,
    ) = context(conn, stream.windowSizeCounter) {
        val buffer = Buffer().apply { write(ByteArray(byteCount)) }
        conn.socketConn.sink.writeData(stream.streamId, outFinished, buffer, byteCount.toLong())
        conn.socketConn.sink.flush()
    }

    suspend fun peerSendHeader(
        outFinished: Boolean,
        streamId: Int,
        headerBlock: List<Header>,
    ) = with(peerConn) {
        peerConn.socketConn.sink.writeHeaders(
            outFinished,
            streamId,
            headerBlock,
        )
        peerConn.socketConn.sink.flush()
    }

    suspend fun peerSendData(
        streamId: Int,
        outFinished: Boolean,
        byteCount: Int,
    ) = context(peerConn, WindowSizeCounter(Int.MAX_VALUE.toLong())) {
        val buffer = Buffer().apply { write(ByteArray(byteCount)) }
        peerConn.socketConn.sink.writeData(streamId, outFinished, buffer, byteCount.toLong())
        peerConn.socketConn.sink.flush()
    }

    suspend fun peerSendRstStream(streamId: Int, errorCode: ErrorCode) = context(peerConn, WindowSizeCounter(Int.MAX_VALUE.toLong())) {
        peerConn.socketConn.sink.writeRstStream(streamId, errorCode)
        peerConn.socketConn.sink.flush()
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

    suspend fun takeFrame(block: suspend Frame.(AsyncSource) -> Unit) {
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
    private val clientPipeConn = openInMemoryPipe()
    private val serverPipeConn = openInMemoryPipe()

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

    val peerConn = Http2Connection(
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

private fun openInMemoryPipe(
    maxBufferSize: Long = 64 * 1024L,
): Pair<AsyncSource, AsyncSink> {
    val pipe = AsyncMemoryPipe(maxBufferSize)
    return pipe.source.buffered() to pipe.sink.buffered()
}

private class AsyncMemoryPipe(
    private val maxBufferSize: Long,
) {
    init {
        require(maxBufferSize > 0)
    }

    private val mutex = Mutex()
    private val buffer = Buffer()

    private var sourceClosed = false
    private var sinkClosed = false

    private val readWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val writeWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    val source: AsyncRawSource = Source()
    val sink: AsyncRawSink = Sink()

    private inner class Source : AsyncRawSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0L)
            if (byteCount == 0L) return 0L

            while (true) {
                val waiter = mutex.withLock {
                    check(!sourceClosed) { "source is closed" }

                    if (buffer.size > 0L) {
                        val readByteCount = min(byteCount, buffer.size)
                        sink.write(buffer, readByteCount)

                        notifyWriters()
                        return readByteCount
                    }

                    if (sinkClosed) {
                        return -1L
                    }

                    CompletableDeferred<Unit>().also {
                        readWaiters.addLast(it)
                    }
                }

                waiter.await()
            }
        }

        override suspend fun close() {
            mutex.withLock {
                if (sourceClosed) return
                sourceClosed = true

                notifyWriters()
                notifyReaders()
            }
        }
    }

    private inner class Sink : AsyncRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            require(byteCount >= 0L)
            require(source.size >= byteCount)

            var remaining = byteCount

            while (remaining > 0L) {
                val waiter = mutex.withLock {
                    check(!sinkClosed) { "sink is closed" }
                    check(!sourceClosed) { "source is closed" }

                    val writableByteCount = maxBufferSize - buffer.size

                    if (writableByteCount > 0L) {
                        val writeByteCount = min(remaining, writableByteCount)

                        buffer.write(source, writeByteCount)
                        remaining -= writeByteCount

                        notifyReaders()
                        null
                    } else {
                        CompletableDeferred<Unit>().also {
                            writeWaiters.addLast(it)
                        }
                    }
                }

                waiter?.await()
            }
        }

        override suspend fun flush() {
            // memory pipe 不需要 flush
        }

        override suspend fun close() {
            mutex.withLock {
                if (sinkClosed) return
                sinkClosed = true

                notifyReaders()
                notifyWriters()
            }
        }
    }

    private fun notifyReaders() {
        while (readWaiters.isNotEmpty()) {
            readWaiters.removeFirst().complete(Unit)
        }
    }

    private fun notifyWriters() {
        while (writeWaiters.isNotEmpty()) {
            writeWaiters.removeFirst().complete(Unit)
        }
    }
}
