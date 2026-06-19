package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kio.http.internal.HttpRequestHead
import kio.network.AsyncRawConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException

internal class Http2Stream(
    val streamId: Int,
    val requestHeader: HttpRequestHead
): AsyncRawConnection {
    private val readableSignal = Channel<Unit>(Channel.CONFLATED)

    private val framingSource = FramingSource(readableSignal)

    override val source: AsyncRawSource = framingSource

    override val sink: AsyncRawSink
        get() = TODO("Not yet implemented")

    suspend fun receiveData(
        source: AsyncSource,
        byteCount: Long,
        inFinished: Boolean,
    ) {
        framingSource.receive(source, byteCount, inFinished)
    }

    override suspend fun close() {
        TODO("Not yet implemented")
    }
}

private class FramingSource(
    val readableSignal: Channel<Unit>
) : AsyncRawSource {
    /** Buffer to receive data from the network into. */
    val receiveBuffer = Buffer()

    /** Buffer with readable data. */
    val readBuffer = Buffer()

    /** True if the caller has closed this stream. */
    var closed: Boolean = false

    /**
     * True if either side has cleanly shut down this stream. We will receive no more bytes beyond
     * those already in the buffer.
     */
    var finished: Boolean = false

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
                readBytesDelivered = readBuffer.readAtMostTo(sink, minOf(byteCount, readBuffer.size))
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