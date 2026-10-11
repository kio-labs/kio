package kio.compression

import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.util.zip.Deflater
import java.util.zip.CRC32

internal class DeflaterSink(
    private val sink: AsyncSink,
    private val deflater: Deflater,
    private val gzip: Boolean = false,
) : AsyncRawSink {
    private val input = ByteArray(CHUNK_SIZE)
    private val output = ByteArray(CHUNK_SIZE)
    private val crc = CRC32()
    private var headerWritten = false
    private var uncompressedSize = 0L
    private var closed = false
    private var failed = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        checkOpen()
        require(byteCount >= 0 && byteCount <= source.size) {
            "byteCount=$byteCount, source.size=${source.size}"
        }
        guard {
            writeHeader()
            var remaining = byteCount
            while (remaining > 0) {
                val count = minOf(remaining, input.size.toLong()).toInt()
                val read = source.readAtMostTo(input, 0, count)
                check(read > 0)
                if (gzip) {
                    crc.update(input, 0, read)
                    uncompressedSize = (uncompressedSize + read) and 0xffffffffL
                }
                deflater.setInput(input, 0, read)
                while (!deflater.needsInput()) {
                    val before = deflater.bytesRead
                    val produced = deflater.deflate(output, 0, output.size, Deflater.NO_FLUSH)
                    if (produced > 0) {
                        sink.write(output, 0, produced)
                    } else if (!deflater.needsInput() && deflater.bytesRead == before) {
                        throw IOException("Deflater made no progress")
                    }
                }
                remaining -= read
            }
        }
    }

    override suspend fun flush() {
        checkOpen()
        guard {
            writeHeader()
            do {
                val produced = deflater.deflate(output, 0, output.size, Deflater.SYNC_FLUSH)
                if (produced > 0) sink.write(output, 0, produced)
            } while (produced == output.size)
            sink.flush()
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            if (!failed) {
                writeHeader()
                deflater.finish()
                while (!deflater.finished()) {
                    val produced = deflater.deflate(output)
                    if (produced > 0) {
                        sink.write(output, 0, produced)
                    } else if (!deflater.finished()) {
                        throw IOException("Deflater made no progress")
                    }
                }
                if (gzip) {
                    writeLittleEndian(crc.value)
                    writeLittleEndian(uncompressedSize)
                }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            deflater.end()
        }
        try {
            sink.close()
        } catch (e: Throwable) {
            if (failure == null) failure = e
            else if (failure !== e) failure.addSuppressed(e)
        }
        failure?.let { throw it }
    }

    private suspend fun writeHeader() {
        if (!gzip || headerWritten) return
        sink.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte()))
        headerWritten = true
    }

    private suspend fun writeLittleEndian(value: Long) {
        repeat(4) { sink.writeByte((value ushr (it * 8)).toByte()) }
    }

    private fun checkOpen() {
        check(!closed) { "closed" }
        check(!failed) { "Compression stream has failed" }
    }

    private suspend inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }
}
