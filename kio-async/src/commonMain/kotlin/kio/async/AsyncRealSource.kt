package kio.async

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.InternalIoApi
import kotlinx.io.RawSink
import kotlin.jvm.JvmField

@InternalIoApi
public class AsyncRealSource(
    public val source: AsyncRawSource
) : AsyncSource {
    @JvmField
    public var closed: Boolean = false
    private val bufferField = Buffer()

    @InternalIoApi
    override val buffer: Buffer
        get() = bufferField

    override suspend fun asyncReadAtMostTo(sink: Buffer, byteCount: Long): Long {
        checkNotClosed()
        require(byteCount >= 0L) { "byteCount: $byteCount" }

        if (bufferField.size == 0L) {
            val read = source.asyncReadAtMostTo(bufferField, SEGMENT_SIZE.toLong())
            if (read == -1L) return -1L
        }

        val toRead = minOf(byteCount, bufferField.size)
        return bufferField.readAtMostTo(sink, toRead)
    }

    override suspend fun exhausted(): Boolean {
        checkNotClosed()
        return bufferField.exhausted() && source.asyncReadAtMostTo(bufferField, SEGMENT_SIZE.toLong()) == -1L
    }

    override suspend fun require(byteCount: Long) {
        if (!request(byteCount)) throw EOFException("Source doesn't contain required number of bytes ($byteCount).")
    }

    override suspend fun request(byteCount: Long): Boolean {
        checkNotClosed()
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        while (bufferField.size < byteCount) {
            if (source.asyncReadAtMostTo(bufferField, SEGMENT_SIZE.toLong()) == -1L) return false
        }
        return true
    }

    override suspend fun readByte(): Byte {
        require(1)
        return bufferField.readByte()
    }

    override suspend fun readAtMostTo(sink: ByteArray, startIndex: Int, endIndex: Int): Int {
        checkBounds(sink.size, startIndex, endIndex)

        if (bufferField.size == 0L) {
            val read = source.asyncReadAtMostTo(bufferField, SEGMENT_SIZE.toLong())
            if (read == -1L) return -1
        }
        val toRead = minOf(endIndex - startIndex, bufferField.size).toInt()
        return bufferField.readAtMostTo(sink, startIndex, startIndex + toRead)
    }

    override suspend fun readTo(sink: RawSink, byteCount: Long) {
        try {
            require(byteCount)
        } catch (e: EOFException) {
            // The underlying source is exhausted. Copy the bytes we got before rethrowing.
            sink.write(bufferField, bufferField.size)
            throw e
        }
        bufferField.readTo(sink, byteCount)
    }

    override suspend fun transferTo(sink: RawSink): Long {
        var totalBytesWritten: Long = 0
        while (source.asyncReadAtMostTo(bufferField, SEGMENT_SIZE.toLong()) != -1L) {
            val emitByteCount = bufferField.completeSegmentByteCount()
            if (emitByteCount > 0L) {
                totalBytesWritten += emitByteCount
                sink.write(bufferField, emitByteCount)
            }
        }
        if (bufferField.size > 0L) {
            totalBytesWritten += bufferField.size
            sink.write(bufferField, bufferField.size)
        }
        return totalBytesWritten
    }

    override suspend fun readShort(): Short {
        require(2)
        return bufferField.readShort()
    }

    override suspend fun readInt(): Int {
        require(4)
        return bufferField.readInt()
    }

    override suspend fun readLong(): Long {
        require(8)
        return bufferField.readLong()
    }

    override suspend fun skip(byteCount: Long) {
        checkNotClosed()
        require(byteCount >= 0) { "byteCount: $byteCount" }
        var remainingByteCount = byteCount
        while (remainingByteCount > 0) {
            if (bufferField.size == 0L && source.asyncReadAtMostTo(bufferField, SEGMENT_SIZE.toLong()) == -1L) {
                throw EOFException(
                    "Source exhausted before skipping $byteCount bytes " +
                            "(only ${remainingByteCount - byteCount} bytes were skipped)."
                )
            }
            val toSkip = minOf(remainingByteCount, bufferField.size)
            bufferField.skip(toSkip)
            remainingByteCount -= toSkip
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        source.close()
        bufferField.clear()
    }

    override fun toString(): String = "buffered($source)"

    @Suppress("NOTHING_TO_INLINE")
    private inline fun checkNotClosed() {
        check(!closed) { "Source is closed." }
    }
}
