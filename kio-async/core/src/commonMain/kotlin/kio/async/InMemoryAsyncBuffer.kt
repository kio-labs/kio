package kio.async

import kotlinx.io.Buffer
import kotlinx.io.InternalIoApi
import kotlinx.io.RawSink
import kotlinx.io.RawSource

fun Buffer.inMemoryAsyncBuffer() = InMemoryAsyncBuffer(this)

/**
 * An in-memory implementation of [AsyncSource] and [AsyncSink].
 *
 * This class stores all written bytes in an internal [Buffer], and reads consume
 * bytes from the same buffer.
 */
class InMemoryAsyncBuffer internal constructor(
    delegate: Buffer = Buffer()
) : AsyncSink, AsyncSource {
    private val internalBuffer = delegate

    val size: Long
        get() = internalBuffer.size

    @InternalIoApi
    override val buffer: Buffer = internalBuffer

    override suspend fun write(
        source: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ) {
        internalBuffer.write(source, startIndex, endIndex)
    }

    override suspend fun transferFrom(source: AsyncRawSource): Long {
        var totalBytesRead = 0L
        while (true) {
            val readCount: Long = source.readAtMostTo(internalBuffer, SEGMENT_SIZE.toLong())
            if (readCount == -1L) break
            totalBytesRead += readCount
        }
        return totalBytesRead
    }

    override suspend fun write(source: RawSource, byteCount: Long) {
        internalBuffer.write(source, byteCount)
    }

    override suspend fun writeByte(byte: Byte) {
        internalBuffer.writeByte(byte)
    }

    override suspend fun writeShort(short: Short) {
        internalBuffer.writeShort(short)
    }

    override suspend fun writeInt(int: Int) {
        internalBuffer.writeInt(int)
    }

    override suspend fun writeLong(long: Long) {
        internalBuffer.writeLong(long)
    }

    override suspend fun flush() = Unit

    override suspend fun emit() = Unit

    @InternalIoApi
    override suspend fun hintEmit() = Unit

    override suspend fun write(source: Buffer, byteCount: Long) {
        internalBuffer.write(source, byteCount)
    }

    override suspend fun close() = Unit

    override suspend fun exhausted(): Boolean {
        return internalBuffer.exhausted()
    }

    override suspend fun require(byteCount: Long) {
        internalBuffer.require(byteCount)
    }

    override suspend fun request(byteCount: Long): Boolean {
        return internalBuffer.request(byteCount)
    }

    override suspend fun readByte(): Byte {
        return internalBuffer.readByte()
    }

    override suspend fun readShort(): Short {
        return internalBuffer.readShort()
    }

    override suspend fun readInt(): Int {
        return internalBuffer.readInt()
    }

    override suspend fun readLong(): Long {
        return internalBuffer.readLong()
    }

    override suspend fun skip(byteCount: Long) {
        internalBuffer.skip(byteCount)
    }

    override suspend fun readAtMostTo(
        sink: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): Int {
        return internalBuffer.readAtMostTo(sink, startIndex, endIndex)
    }

    override suspend fun readTo(sink: RawSink, byteCount: Long) {
        internalBuffer.readTo(sink, byteCount)
    }

    override suspend fun transferTo(sink: RawSink): Long {
        return internalBuffer.transferTo(sink)
    }

    override suspend fun transferTo(sink: AsyncRawSink): Long {
        TODO("Not yet implemented")
    }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        return internalBuffer.readAtMostTo(sink, byteCount)
    }
}