@file:OptIn(ExperimentalForeignApi::class)

package kio.tls

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlin.math.min
import openssl.*

internal const val CHUNK_SIZE = 8192

internal class SslSource(
    private val rbio: CPointer<BIO>,
    private val ssl: CPointer<SSL>,
    private val source: AsyncSource,
    private val bufferChunkSize: Int = CHUNK_SIZE,
) : AsyncRawSource {

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long
    ): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        val output = ByteArray(min(bufferChunkSize.toLong(), byteCount).toInt())

        while (true) {
            val read = output.usePinned { pinned ->
                SSL_read(ssl, pinned.addressOf(0), output.size)
            }

            if (read > 0) {
                sink.write(output, 0, read)
                return read.toLong()
            }

            when (val err = SSL_get_error(ssl, read)) {
                SSL_ERROR_WANT_READ -> {
                    if (!feedRbioFromSource(rbio, source)) {
                        return -1L
                    }
                }

                SSL_ERROR_WANT_WRITE -> {
                    return 0L
                }

                SSL_ERROR_ZERO_RETURN -> {
                    return -1L
                }

                else -> {
                    throw kotlinx.io.IOException("SSL_read failed: $err")
                }
            }
        }
    }

    override suspend fun close() {
        source.close()
    }
}