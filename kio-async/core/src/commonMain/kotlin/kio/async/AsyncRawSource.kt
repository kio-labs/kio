package kio.async

import kotlinx.io.Buffer

public interface AsyncRawSource: AutoCloseable {
    override fun close()
    public suspend fun asyncReadAtMostTo(sink: Buffer, byteCount: Long): Long
}
