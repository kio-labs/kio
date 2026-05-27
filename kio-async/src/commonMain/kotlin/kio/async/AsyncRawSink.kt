package kio.async

import kotlinx.io.Buffer

public interface AsyncRawSink: AutoCloseable {
    public suspend fun write(source: Buffer, byteCount: Long)

    public suspend fun flush()

    override fun close()
}
