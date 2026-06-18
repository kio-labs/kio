package kio.http.internal.http1

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kio.http.internal.Drainable
import kotlinx.io.Buffer
import kotlinx.io.IOException

internal fun AsyncSource.chunked(): AsyncRawSource = ChunkedSource(this)

internal class ChunkedSource(
    val source: AsyncSource
) : AsyncRawSource, Drainable {
    private val buffer = Buffer()
    internal var sourceChunkExhausted = false
        private set

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long
    ): Long {
        if (sourceChunkExhausted && buffer.exhausted()) return -1

        val write = buffer.readAtMostTo(sink, byteCount)
        if (write != -1L) {
            return write
        }

        // buffer exhausted, read chunk to buffer.
        val count = readChunkToBuffer(source, buffer)
        if (count == 0L) sourceChunkExhausted = true

        return buffer.readAtMostTo(sink, byteCount)
    }

    override suspend fun close() {}


    override suspend fun drain() {
        if (!sourceChunkExhausted) {
            while (true) {
                val count = readChunkToBuffer(source, buffer)
                if (count == 0L) {
                    sourceChunkExhausted = true
                    break
                }

                buffer.clear()
            }
        }
    }
}

private suspend fun readChunkToBuffer(source: AsyncSource, target: Buffer): Long {
    val countStr = source.readCrlfLine()
    val count = countStr.toLongOrNull(16) ?: throw IOException("invalid chunk count ($countStr).")
    if (count == 0L) {
        // Skip /r/n and finish.
        source.skip(2)
        return 0
    }

    source.readTo(target, count)
    source.skip(2)
    return count
}
