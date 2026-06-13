package kio.async

import kotlinx.io.Buffer
import kotlinx.io.EOFException

fun AsyncRawSource.limited(limit: Long): LimitedSource = LimitedSource(this, limit)

class LimitedSource(
    private val upstream: AsyncRawSource,
    private var remaining: Long,
) : AsyncRawSource {
    val exhausted: Boolean
        get() = remaining == 0L

    val bytesRemaining: Long
        get() = remaining

    override suspend fun asyncReadAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (remaining == 0L) return -1L

        val toRead = minOf(byteCount, remaining)
        val read = upstream.asyncReadAtMostTo(sink, toRead)

        if (read == -1L) {
            throw EOFException("Unexpected EOF while reading request body")
        }

        remaining -= read
        return read
    }

    override fun close() {}
}