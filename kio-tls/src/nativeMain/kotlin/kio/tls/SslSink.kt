@file:OptIn(ExperimentalForeignApi::class)

package kio.tls

import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import openssl.*

internal class SslSink(
    private val wbio: CPointer<BIO>,
    private val ssl: CPointer<SSL>,
    private val sink: AsyncSink,
    private val bufferChunkSize: Int = CHUNK_SIZE,
) : AsyncRawSink {
    override suspend fun write(source: Buffer, byteCount: Long) {
        writeBytesFromSource(source, byteCount, sink)
    }

    @OptIn(UnsafeIoApi::class)
    private suspend fun writeBytesFromSource(
        source: Buffer,
        sourceExactByteCount: Long,
        target: AsyncSink,
    ) {
        var remaining = sourceExactByteCount
        val outputChunk = ByteArray(bufferChunkSize)
        var sourceRead = 0

        while (remaining > 0L) {
            UnsafeBufferOperations.readFromHead(source) { data, pos, limit ->
                data.asUByteArray().usePinned { pinnedInput ->
                    val len = minOf(limit - pos, remaining.toInt())

                    val ret = SSL_write(
                        ssl,
                        pinnedInput.addressOf(pos),
                        len
                    )

                    if (ret <= 0) {
                        val err = SSL_get_error(ssl, ret)
                        when (err) {
                            SSL_ERROR_WANT_READ,
                            SSL_ERROR_WANT_WRITE -> {
                                sourceRead = 0
                                0
                            }
                            else -> throw IOException("SSL_write failed: $err")
                        }
                    } else {
                        sourceRead = ret
                        ret
                    }
                }
            }

            flushWbioToSink(wbio, target, outputChunk)

            if (sourceRead == 0) {
                continue
            }

            remaining -= sourceRead
        }
    }

    override suspend fun flush() {
        val outputChunk = ByteArray(bufferChunkSize)
        flushWbioToSink(wbio, sink, outputChunk)
        sink.flush()
    }

    override fun close() {
        sink.close()
    }
}