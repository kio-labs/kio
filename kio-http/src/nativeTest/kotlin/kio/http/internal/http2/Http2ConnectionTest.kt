package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.buffered
import kio.async.openPipe
import kio.async.poller.poll.PosixPoll
import kio.async.runPollEventLoop
import kio.network.AsyncRawConnection
import kio.network.buffered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class Http2ConnectionTest {
    @Test
    fun serverCanAckPing() = withHttp2Test {
        clientPing(4, 23)
        takeServerFrame {
            assertIs<Frame.PingAck>(this)
            assertEquals(4, payload1)
            assertEquals(23,payload2)
        }
    }

    @Test
    fun serverCanSendAckSettingFrameWhenReceiveSettingFrame() = withHttp2Test {
        clientSetting(Settings())
        takeServerFrame { assertIs<Frame.SettingsAck>(this) }
    }

    @Test
    fun streamCanCreatedWhenReceiveHeaderFrame() = withHttp2Test {
        clientSetting(Settings())
        takeServerFrame {}

        clientHeader(true, 1, listOf(Header("a", "value")))
        val stream = assertStreamCreated()
        assertEquals(1, stream.streamId)
    }

    @Test
    fun streamSourceExhaustedWhenNoRequestBody() = withHttp2Test{
        clientSetting(Settings())
        takeServerFrame {}

        clientHeader(true, 1, listOf(Header("a", "value")))
        val stream = assertStreamCreated()
        assertTrue(stream.source.buffered().exhausted())
    }

    @Test
    fun changeInitialWindowSizeSettings() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        clientSetting(initial)
        takeServerFrame { assertIs<Frame.SettingsAck>(this) }

        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        clientSetting(shouldntImpactConnection)
        takeServerFrame { assertIs<Frame.SettingsAck>(this) }
        assertEquals(3368, conn.peerSettings.initialWindowSize)

        clientHeader(true, 1, listOf(Header("a", "value")))
        val stream = assertStreamCreated()
        // New Stream is has the most recent initial window size.
        assertEquals(3368, stream.writeBytesMaximum)
    }

    @Test
    fun streamMaximumWriteSizeChangedAfterCreated() = withHttp2Test {
        val initial = Settings()
        initial[Settings.INITIAL_WINDOW_SIZE] = 1684
        clientSetting(initial)
        takeServerFrame { assertIs<Frame.SettingsAck>(this) }

        // create stream before change.
        clientHeader(true, 1, listOf(Header("a", "value")))
        val stream = assertStreamCreated()

        // Change INITIAL_WINDOW_SIZE
        val shouldntImpactConnection = Settings()
        shouldntImpactConnection[Settings.INITIAL_WINDOW_SIZE] = 3368
        clientSetting(shouldntImpactConnection)
        takeServerFrame { assertIs<Frame.SettingsAck>(this) }

        // new size applied.
        assertEquals(3368, stream.writeBytesMaximum)
    }
}

private fun withHttp2Test(block: suspend Http2TestScope.() -> Unit) =
    runPollEventLoop(PosixPoll) {
        val mock = MockHttp2Connection(this)
        val scop = Http2TestScope(mock, this.coroutineContext)

        val job = launch {
            mock.http2Conn.frameReadLoop()
        }

        block(scop)
        mock.handlerCompleter.complete(Unit)
        job.cancel()
    }

private class Http2TestScope(
    private val mockConn: MockHttp2Connection,
    override val coroutineContext: CoroutineContext
) : CoroutineScope {
    val conn: Http2Connection = mockConn.http2Conn

    private val clientMutex = Mutex()
    private val clientPpackBuffer = Buffer()
    private val clientPpackWriter: Hpack.Writer = Hpack.Writer(out = clientPpackBuffer)

    suspend fun clientPing(payload1: Int, payload2: Int) = with(clientMutex) {
        mockConn.clientSink.writePing(false, payload1, payload2)
        mockConn.clientSink.flush()
    }

    suspend fun clientSetting(
        settings: Settings
    ) = with(clientMutex) {
        mockConn.clientSink.writeSetting(settings)
        mockConn.clientSink.flush()
    }

    suspend fun clientHeader(
        outFinished: Boolean,
        streamId: Int,
        headerBlock: List<Header>,
    ) = with(clientMutex) {
        mockConn.clientSink.writeHeaders(
            outFinished,
            streamId,
            headerBlock,
            clientPpackBuffer,
            clientPpackWriter,
        )
        mockConn.clientSink.flush()
    }

    suspend fun assertStreamCreated(): Http2Stream {
        return withTimeout(100.milliseconds) {
            assertNotNull(mockConn.createdHttp2Stream.await())
        }
    }

    private val continuation = ContinuationSource(mockConn.serverWriteBackSource)
    private val hpackReader: Hpack.Reader =
        Hpack.Reader(
            source = continuation,
            headerTableSizeSetting = 4096,
        )

    suspend fun takeServerFrame(block: Frame.() -> Unit) {
        val frame = context(hpackReader, continuation) {
            withTimeout(100.milliseconds) {
                mockConn.serverWriteBackSource.nextFrame()
            }
        }
        block(frame)
    }
}

private class MockHttp2Connection(
    val testScope: CoroutineScope
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

    var createdHttp2Stream: CompletableDeferred<Http2Stream> = CompletableDeferred()

    val serverWriteMutex = Mutex()
    val handlerCompleter = CompletableDeferred<Unit>()
    val http2Conn = Http2Connection(
        serverConnection.buffered(),
        serverWriteMutex,
        testScope,
    ) {
        createdHttp2Stream.complete(it)
        handlerCompleter.await()
    }
}