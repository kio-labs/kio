@file:OptIn(ExperimentalForeignApi::class)

package kio.compression

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.unsafe.withData
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DATA_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream_s
import kotlin.math.min

internal class InflaterSource(
    private val source: AsyncSource,
    private val windowBits: Int,
    private val bufferChunkSize: Int = CHUNK_SIZE
) : AsyncRawSource {
    private var finished: Boolean = false
    private var closed: Boolean = false

    private val zStream: z_stream_s = nativeHeap.alloc<z_stream_s> {
        zalloc = null
        zfree = null
        opaque = null
        check(inflateInit2(strm = ptr, windowBits = windowBits) == Z_OK)
    }

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }

        return readBytesToTarget(
            source = source,
            targetMaxByteCount = byteCount,
            target = sink,
        )
    }

    override suspend fun close() {
        if (closed) return

        inflateEnd(zStream.ptr)
        nativeHeap.free(zStream)
        closed = true

        source.close()
    }

    @OptIn(UnsafeIoApi::class, InternalIoApi::class)
    private suspend fun readBytesToTarget(
        source: AsyncSource,
        targetMaxByteCount: Long,
        target: Buffer,
    ): Long {
        check(!closed) { "closed" }

        if (source.exhausted()) return -1L
        val outputChunk = ByteArray(bufferChunkSize)
        var byteCount = 0L
        var sourceRead = 0
        var targetWrite = 0L
        var inflateResult = -1
        while (true) {
            var reachOutputLimit = false
            UnsafeBufferOperations.readFromHead(source.buffer) { readContext, segment ->
                readContext.withData(segment) { inputArray, pos, limit ->
                    inputArray.asUByteArray().usePinned { pinnedInput ->
                        outputChunk.asUByteArray().usePinned { pinnedOutput ->
                            val sourceByteCount = limit - pos
                            zStream.next_in = pinnedInput.addressOf(pos)
                            zStream.avail_in = (limit - pos).toUInt()

                            val targetByteCount = min(bufferChunkSize.toLong(), (targetMaxByteCount - byteCount))
                            zStream.next_out = pinnedOutput.addressOf(0)
                            zStream.avail_out = targetByteCount.toUInt()

                            inflateResult = inflate(zStream.ptr, Z_NO_FLUSH)

                            sourceRead = sourceByteCount - zStream.avail_in.toInt()
                            targetWrite = targetByteCount - zStream.avail_out.toInt()
                            if (zStream.avail_out.toInt() == 0) reachOutputLimit = true
                        }
                    }
                }
                sourceRead
            }

            target.write(outputChunk, 0, targetWrite.toInt())
            byteCount += targetWrite

            when (inflateResult) {
                Z_OK, Z_BUF_ERROR -> {
                    if (!source.buffer.exhausted() && reachOutputLimit && byteCount < targetMaxByteCount) continue
                }

                Z_STREAM_END -> {
                    finished = true
                }

                Z_DATA_ERROR -> throw IOException("Z_DATA_ERROR")

                // One of Z_NEED_DICT, Z_STREAM_ERROR, Z_MEM_ERROR.
                else -> throw IOException("unexpected inflate result: $inflateResult")
            }

            break
        }

        return when {
            byteCount > 0 -> byteCount
            else -> -1L
        }
    }
}