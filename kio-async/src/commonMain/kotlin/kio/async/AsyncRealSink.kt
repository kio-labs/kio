package kio.async

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.InternalIoApi
import kotlinx.io.RawSource

@OptIn(InternalIoApi::class)
public class AsyncRealSink(
    public val sink: AsyncRawSink
) : AsyncSink {
    public var closed: Boolean = false
    private val bufferField = Buffer()

    override val buffer: Buffer
        get() = bufferField

    override suspend fun write(source: Buffer, byteCount: Long) {
        checkNotClosed()
        require(byteCount >= 0) { "byteCount: $byteCount" }
        bufferField.write(source, byteCount)
        hintEmit()
    }

    override suspend fun write(source: ByteArray, startIndex: Int, endIndex: Int) {
        checkNotClosed()
        checkBounds(source.size, startIndex, endIndex)
        bufferField.write(source, startIndex, endIndex)
        hintEmit()
    }

    override suspend fun transferFrom(source: RawSource): Long {
        checkNotClosed()
        var totalBytesRead = 0L
        while (true) {
            val readCount: Long = source.readAtMostTo(bufferField, SEGMENT_SIZE.toLong())
            if (readCount == -1L) break
            totalBytesRead += readCount
            hintEmit()
        }
        return totalBytesRead
    }

    override suspend fun write(source: RawSource, byteCount: Long) {
        checkNotClosed()
        require(byteCount >= 0) { "byteCount: $byteCount" }
        var remainingByteCount = byteCount
        while (remainingByteCount > 0L) {
            val read = source.readAtMostTo(bufferField, remainingByteCount)
            if (read == -1L) {
                val bytesRead = byteCount - remainingByteCount
                throw EOFException(
                    "Source exhausted before reading $byteCount bytes from it (number of bytes read: $bytesRead)."
                )
            }
            remainingByteCount -= read
            hintEmit()
        }
    }

    override suspend fun writeByte(byte: Byte) {
        checkNotClosed()
        bufferField.writeByte(byte)
        hintEmit()
    }

    override suspend fun writeShort(short: Short) {
        checkNotClosed()
        bufferField.writeShort(short)
        hintEmit()
    }

    override suspend fun writeInt(int: Int) {
        checkNotClosed()
        bufferField.writeInt(int)
        hintEmit()
    }

    override suspend fun writeLong(long: Long) {
        checkNotClosed()
        bufferField.writeLong(long)
        hintEmit()
    }

    @InternalIoApi
    override suspend fun hintEmit() {
        checkNotClosed()
        val byteCount = bufferField.completeSegmentByteCount()
        if (byteCount > 0L) sink.write(bufferField, byteCount)
    }

    override suspend fun emit() {
        checkNotClosed()
        val byteCount = bufferField.size
        if (byteCount > 0L) sink.write(bufferField, byteCount)
    }

    override suspend fun flush() {
        checkNotClosed()
        if (bufferField.size > 0L) {
            sink.write(bufferField, bufferField.size)
        }
        sink.flush()
    }

    override fun close() {
        if (closed) return

        // Emit buffered data to the underlying sink. If this fails, we still need
        // to close the sink; otherwise we risk leaking resources.
        var thrown: Throwable? = null

// TODO: can not support flush when close.
//        try {
//            if (bufferField.size > 0) {
//                sink.write(bufferField, bufferField.size)
//            }
//        } catch (e: Throwable) {
//            thrown = e
//        }

        try {
            sink.close()
        } catch (e: Throwable) {
            if (thrown == null) thrown = e
        }

        closed = true

        if (thrown != null) throw thrown
    }

    override fun toString(): String = "buffered($sink)"

    @Suppress("NOTHING_TO_INLINE")
    private inline fun checkNotClosed() {
        check(!closed) { "Sink is closed." }
    }
}
