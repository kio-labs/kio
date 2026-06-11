@file:OptIn(ExperimentalForeignApi::class)

package kio.tls

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.InternalIoApi
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.unsafe.withData
import openssl.BIO_write
import kotlin.math.min

internal const val CHUNK_SIZE = 8192

internal class SslSource(
    private val rbio: CPointer<openssl.BIO>,
    private val ssl: CPointer<openssl.SSL>,
    private val source: AsyncSource,
    private val bufferChunkSize: Int = CHUNK_SIZE
) : AsyncRawSource {

    override suspend fun asyncReadAtMostTo(
        sink: Buffer,
        byteCount: Long
    ): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        val output = ByteArray(min(bufferChunkSize.toLong(), byteCount).toInt())

        while (true) {
            val read = output.usePinned { pinned ->
                openssl.SSL_read(ssl, pinned.addressOf(0), output.size)
            }

            if (read > 0) {
                sink.write(output, 0, read)
                return read.toLong()
            }

            when (val err = openssl.SSL_get_error(ssl, read)) {
                openssl.SSL_ERROR_WANT_READ -> {
                    if (!feedRbioFromSource(source)) {
                        return -1L
                    }
                }

                openssl.SSL_ERROR_WANT_WRITE -> {
                    return 0L
                }

                openssl.SSL_ERROR_ZERO_RETURN -> {
                    return -1L
                }

                else -> {
                    throw kotlinx.io.IOException("SSL_read failed: $err")
                }
            }
        }
    }

    @OptIn(UnsafeIoApi::class, InternalIoApi::class)
    private suspend fun feedRbioFromSource(source: AsyncSource): Boolean {
        if (source.exhausted()) return false

        var consumed = 0

        UnsafeBufferOperations.readFromHead(source.buffer) { readContext, segment ->
            readContext.withData(segment) { inputArray, pos, limit ->
                inputArray.asUByteArray().usePinned { pinnedInput ->
                    val len = limit - pos
                    val written = BIO_write(rbio, pinnedInput.addressOf(pos), len)

                    if (written <= 0) {
                        throw kotlinx.io.IOException("BIO_write(rbio) failed")
                    }

                    consumed = written
                }
            }

            consumed
        }

        return consumed > 0
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}