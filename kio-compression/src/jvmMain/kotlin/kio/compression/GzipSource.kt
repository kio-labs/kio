package kio.compression

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Reads concatenated gzip members; rejects trailing bytes that are not another member. */
internal class GzipSource(private val source: AsyncSource) : AsyncRawSource {
    private val crc = CRC32()
    private val output = ByteArray(CHUNK_SIZE)
    private val decoded = Buffer()
    private var inflater: InflaterSource? = null
    private var size = 0L
    private var closed = false
    private var failed = false
    private var finished = false

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        check(!failed) { "Gzip stream has failed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        if (finished) return -1
        try {
            while (true) {
                if (inflater == null) {
                    readHeader()
                    crc.reset()
                    size = 0
                    inflater = InflaterSource(source, Inflater(true), closeSource = false)
                }
                val count = inflater!!.readAtMostTo(decoded, minOf(byteCount, output.size.toLong()))
                if (count > 0) {
                    var offset = 0
                    while (offset < count.toInt()) {
                        val read = decoded.readAtMostTo(output, offset, count.toInt())
                        check(read > 0)
                        offset += read
                    }
                    crc.update(output, 0, offset)
                    size = (size + offset) and 0xffffffffL
                    sink.write(output, 0, offset)
                    return count
                }
                inflater!!.close()
                inflater = null
                if (readLittleEndian(4) != crc.value) throw IOException("Gzip CRC32 mismatch")
                if (readLittleEndian(4) != size) throw IOException("Gzip size mismatch")
                if (!source.request(1)) {
                    finished = true
                    return -1
                }
            }
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    private suspend fun readHeader() {
        val headerCrc = CRC32()
        suspend fun byte(): Int = readByte().also { headerCrc.update(it) }
        if (byte() != 0x1f || byte() != 0x8b) throw IOException("Invalid gzip magic")
        if (byte() != 8) throw IOException("Unsupported gzip compression method")
        val flags = byte()
        if (flags and 0xe0 != 0) throw IOException("Reserved gzip flags are set")
        repeat(6) { byte() } // MTIME, XFL, OS
        if (flags and 4 != 0) {
            val length = byte() or (byte() shl 8)
            repeat(length) { byte() }
        }
        if (flags and 8 != 0) while (byte() != 0) { /* FNAME */ }
        if (flags and 16 != 0) while (byte() != 0) { /* FCOMMENT */ }
        if (flags and 2 != 0 && readLittleEndian(2) != (headerCrc.value and 0xffffL)) {
            throw IOException("Gzip header CRC mismatch")
        }
    }

    private suspend fun readByte(): Int {
        if (!source.request(1)) throw IOException("Truncated gzip stream")
        return source.readByte().toInt() and 0xff
    }

    private suspend fun readLittleEndian(count: Int): Long {
        var result = 0L
        repeat(count) { result = result or (readByte().toLong() shl (8 * it)) }
        return result
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        try {
            inflater?.close()
        } finally {
            source.close()
        }
    }
}
