package kio.async

import kotlinx.io.Buffer
import kotlinx.io.RawSink

public sealed interface AsyncSource : AsyncRawSource {
  public val buffer: Buffer

  public suspend fun exhausted(): Boolean

  public suspend fun require(byteCount: Long)

  public suspend fun request(byteCount: Long): Boolean

  public suspend fun readByte(): Byte

  public suspend fun readShort(): Short

  public suspend fun readInt(): Int

  public suspend fun readLong(): Long

  public suspend fun skip(byteCount: Long)

  public suspend fun readAtMostTo(sink: ByteArray, startIndex: Int = 0, endIndex: Int = sink.size): Int

  public suspend fun readTo(sink: RawSink, byteCount: Long)

  public suspend fun transferTo(sink: RawSink): Long
  public suspend fun transferTo(sink: AsyncRawSink): Long
}
