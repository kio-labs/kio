package kio.http.internal

import kio.async.AsyncRawSource
import kotlinx.io.Buffer
import kotlinx.io.EOFException

internal fun AsyncRawSource.limited(limit: Long): LimitedSource = LimitedSource(this, limit)

internal class LimitedSource(
    private val upstream: AsyncRawSource,
    private var remaining: Long,
) : AsyncRawSource, Drainable {
    val exhausted: Boolean
        get() = remaining == 0L

    val bytesRemaining: Long
        get() = remaining

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (remaining == 0L) return -1L

        val toRead = minOf(byteCount, remaining)
        val read = upstream.readAtMostTo(sink, toRead)

        if (read == -1L) {
            throw EOFException("Unexpected EOF while reading request body")
        }

        remaining -= read
        return read
    }

    override suspend fun close() {}

    override suspend fun drain() {
        val source = this
        if (source.exhausted) return

        val buffer = Buffer()

        while (!source.exhausted) {
            buffer.clear()

            val read = source.readAtMostTo(
                sink = buffer,
                byteCount = minOf(8192L, source.bytesRemaining)
            )

            if (read == -1L) break

            buffer.skip(read)
        }
    }
}
