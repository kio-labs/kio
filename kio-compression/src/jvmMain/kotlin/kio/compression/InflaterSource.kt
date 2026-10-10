package kio.compression

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal class InflaterSource(
    private val source: AsyncSource,
    private val inflater: Inflater,
    private val closeSource: Boolean = true,
) : AsyncRawSource {
    private val input = ByteArray(CHUNK_SIZE)
    private val output = ByteArray(CHUNK_SIZE)
    private var closed = false
    private var finished = false
    private var failed = false

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        check(!failed) { "Decompression stream has failed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        if (finished) return -1
        try {
            while (true) {
                if (inflater.needsInput()) {
                    if (!source.request(1)) throw IOException("Truncated compressed stream")
                    val size = minOf(source.buffer.size, input.size.toLong()).toInt()
                    // Keep unconsumed bytes available for a trailer or the next stream.
                    val copy = Buffer()
                    source.buffer.copyTo(copy, 0, size.toLong())
                    var offset = 0
                    while (offset < size) {
                        val read = copy.readAtMostTo(input, offset, size)
                        check(read > 0)
                        offset += read
                    }
                    inflater.setInput(input, 0, size)
                }
                val before = inflater.remaining
                val produced = inflater.inflate(output, 0, minOf(byteCount, output.size.toLong()).toInt())
                val consumed = before - inflater.remaining
                source.buffer.skip(consumed.toLong())
                if (inflater.finished()) finished = true
                if (produced > 0) {
                    sink.write(output, 0, produced)
                    return produced.toLong()
                }
                if (finished) return -1
                if (inflater.needsDictionary()) {
                    throw IOException("Compressed stream requires a preset dictionary")
                }
                if (consumed == 0 && !inflater.needsInput()) {
                    throw IOException("Inflater made no progress")
                }
            }
        } catch (e: DataFormatException) {
            failed = true
            throw IOException("Invalid compressed data", e)
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            inflater.end()
        } finally {
            if (closeSource) source.close()
        }
    }
}
