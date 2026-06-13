package kio.async

import kotlinx.io.Buffer

interface AsyncRawSource {
    suspend fun close()
    suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long
}

