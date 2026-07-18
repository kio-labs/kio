package kio.async

import kotlinx.io.Buffer
import kotlinx.io.RawSource

public sealed interface AsyncSink : AsyncRawSink {
    public val buffer: Buffer

    public suspend fun write(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size)

    public suspend fun transferFrom(source: AsyncRawSource): Long

    public suspend fun write(source: RawSource, byteCount: Long)

    public suspend fun writeByte(byte: Byte)

    public suspend fun writeShort(short: Short)

    public suspend fun writeInt(int: Int)

    public suspend fun writeLong(long: Long)

    override suspend fun flush()

    public suspend fun emit()

    public suspend fun hintEmit()
}
