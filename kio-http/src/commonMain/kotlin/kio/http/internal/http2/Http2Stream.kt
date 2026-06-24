package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.http.internal.HttpRequestHead
import kio.http.internal.http2.Http2.INITIAL_MAX_FRAME_SIZE
import kio.network.AsyncRawConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException

internal class Http2Stream constructor(
    val streamId: Int,
    val requestHead: HttpRequestHead,
    sourceFinished: Boolean,
    http2Conn: Http2Connection,
) : AsyncRawConnection {
    lateinit var scope: CoroutineScope
    private val socketSink: AsyncSink = http2Conn.socketConn.sink

    /** The total number of bytes permitted to be produced by incoming `WINDOW_UPDATE` frame. */
    var writeBytesMaximum: Long = http2Conn.peerSettings.initialWindowSize.toLong()
        internal set

    private val framingSource = FramingSource(sourceFinished)
    private val frameSink = FramingSink(streamId, socketSink, http2Conn)

    override val source: AsyncRawSource = framingSource

    override val sink: AsyncRawSink = frameSink

    suspend fun receiveData(source: AsyncSource, byteCount: Long, inFinished: Boolean) {
        framingSource.receive(source, byteCount, inFinished)
    }

    override suspend fun close() {
        TODO("Not yet implemented")
    }

    fun addBytesToWriteWindow(delta: Long) {
        writeBytesMaximum += delta
    }

    override fun toString(): String {
        return "Http2Stream[$streamId]"
    }
}

private class FramingSink(
    private val streamId: Int,
    private val socketSink: AsyncSink,
    private val http2Connection: Http2Connection
) : AsyncRawSink {
    /**
     * Buffer of outgoing data. This batches writes of small writes into this sink as larges frames
     * written to the outgoing connection. Batching saves the (small) framing overhead.
     */
    private val sendBuffer = Buffer()

    private var closed: Boolean = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        if (closed) throw IOException("stream closed")

        sendBuffer.write(source, byteCount)

        while (sendBuffer.size >= EMIT_BUFFER_SIZE) {
            emitFrame(false)
        }
    }

    /**
     * Emit a single data frame to the connection. The frame's size be limited by this stream's
     * write window. This method will block until the write window is nonempty.
     */
    private suspend fun emitFrame(outFinishedOnLastFrame: Boolean) {
        with(http2Connection) {
            // TODO: await io if no more WINDOW_SIZE
            val toWrite = minOf(sendBuffer.size, INITIAL_MAX_FRAME_SIZE.toLong())
            val outFinished = outFinishedOnLastFrame && toWrite == sendBuffer.size
            socketSink.writeData(streamId, outFinished, sendBuffer, toWrite)
        }
    }

    override suspend fun flush() {
        while (sendBuffer.size > 0L) {
            emitFrame(false)
        }
        socketSink.flush()
    }

    override suspend fun close() {
        val hasData = sendBuffer.size > 0
        when {
            hasData -> {
                while (sendBuffer.size > 0L) {
                    emitFrame(true)
                }
            }

            else -> {
                with (http2Connection) {
                    socketSink.writeData(streamId, true, null, 0L)
                }
            }
        }
        socketSink.flush()

        closed = true
    }

    companion object {
        const val EMIT_BUFFER_SIZE = 16384L
    }
}

private class FramingSource(
    /**
     * True if either side has cleanly shut down this stream. We will receive no more bytes beyond
     * those already in the buffer.
     */
    var finished: Boolean = false
) : AsyncRawSource {
    private val readableSignal = Channel<Unit>(Channel.CONFLATED)

    /** Buffer to receive data from the network into. */
    val receiveBuffer = Buffer()

    /** Buffer with readable data. */
    val readBuffer = Buffer()

    /** True if the caller has closed this stream. */
    var closed: Boolean = false

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long
    ): Long {
        var readBytesDelivered = -1L
        var tryAgain = false

        while (true) {
            if (closed) {
                throw IOException("stream closed")
            } else if (readBuffer.size > 0L) {
                readBytesDelivered =
                    readBuffer.readAtMostTo(sink, minOf(byteCount, readBuffer.size))
            } else if (!finished) {
                readableSignal.receive()
                tryAgain = true
            }

            if (tryAgain) {
                continue
            }

            if (readBytesDelivered != -1L) {
                return readBytesDelivered
            }

            return -1L // This source is exhausted.
        }
    }

    override suspend fun close() {
        readBuffer.clear()
        closed = true
    }

    suspend fun receive(
        source: AsyncSource,
        byteCount: Long,
        inFinished: Boolean,
    ) {
        var remainingByteCount = byteCount

        while (remainingByteCount > 0L) {
            val read = source.readAtMostTo(receiveBuffer, remainingByteCount)
            if (read == -1L) throw EOFException()
            remainingByteCount -= read

            val wasEmpty = readBuffer.size == 0L
            readBuffer.transferFrom(receiveBuffer)
            if (wasEmpty) {
                readableSignal.trySend(Unit)
            }
        }

        if (inFinished) {
            this.finished = true
            readableSignal.trySend(Unit)
        }
    }
}