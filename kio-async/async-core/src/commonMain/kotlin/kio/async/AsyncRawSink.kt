package kio.async

import kotlinx.io.Buffer

interface AsyncRawSink {
    suspend fun write(source: Buffer, byteCount: Long)

    suspend fun flush()

    suspend fun close()
}
