@file:OptIn(ExperimentalForeignApi::class)

package kio.compression

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_STREAM_ERROR
import platform.zlib.Z_SYNC_FLUSH
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.z_stream_s

internal class DeflaterSink(
    private val sink: Sink,
    private val level: Int,
    private val windowBits: Int,
    private val bufferChunkSize: Int = CHUNK_SIZE
) : RawSink {
    private var closed: Boolean = false
    private var finished: Boolean = false

    private val zStream: z_stream_s = nativeHeap.alloc<z_stream_s> {
        zalloc = null
        zfree = null
        opaque = null
        check(
            deflateInit2(
                strm = ptr,
                level = level,
                method = Z_DEFLATED,
                windowBits = windowBits,
                memLevel = 8, // Default value.
                strategy = Z_DEFAULT_STRATEGY,
            ) == Z_OK,
        )
    }

    override fun write(source: Buffer, byteCount: Long) {
        writeBytesFromSource(source, byteCount, sink)
    }

    override fun flush() {
        check(!closed) { "closed" }
        doFlush(sink, Z_SYNC_FLUSH)
        sink.flush()
    }

    override fun close() {
        if (closed) return

        // We must close the deflater and the target, even if flushing fails. Otherwise, we'll leak
        // resources! (And we re-throw whichever exception we catch first.)
        var thrown: Throwable? = null

        try {
            doFlush(sink, Z_FINISH)
        } catch (e: Throwable) {
            thrown = e
        }

        deflateEnd(zStream.ptr)
        nativeHeap.free(zStream)
        closed = true

        try {
            sink.close()
        } catch (e: Throwable) {
            if (thrown == null) thrown = e
        }

        if (thrown != null) throw thrown
    }

    @OptIn(UnsafeIoApi::class)
    private fun writeBytesFromSource(
        source: Buffer,
        sourceExactByteCount: Long,
        target: Sink,
    ) {
        check(!closed) { "closed" }

        val outputChunk = ByteArray(bufferChunkSize)
        val targetByteCount = bufferChunkSize
        var remaining = sourceExactByteCount
        var sourceRead = 0L
        var targetWrite = 0
        var deflateResult = -1
        while (true) {
            var reachOutputLimit = false
            UnsafeBufferOperations.readFromHead(source) { data, pos, limit ->
                val sourceByteCount = minOf(remaining, (limit - pos).toLong())
                data.asUByteArray().usePinned { pinnedInput ->
                    outputChunk.asUByteArray().usePinned { pinnedOutput ->
                        zStream.next_in = pinnedInput.addressOf(pos)
                        zStream.avail_in = sourceByteCount.toUInt()

                        zStream.next_out = pinnedOutput.addressOf(0)
                        zStream.avail_out = targetByteCount.toUInt()

                        deflateResult = deflate(zStream.ptr, Z_NO_FLUSH)
                        check(deflateResult != Z_STREAM_ERROR)

                        sourceRead = sourceByteCount - zStream.avail_in.toInt()
                        targetWrite = targetByteCount - zStream.avail_out.toInt()
                        if (zStream.avail_out.toInt() == 0) reachOutputLimit = true
                    }
                }
                sourceRead.toInt()
            }
            target.write(outputChunk, 0, targetWrite)
            remaining -= sourceRead

            when (deflateResult) {
                Z_STREAM_END -> {
                    finished = true
                }

                else -> if (reachOutputLimit || remaining != 0L) continue
            }

            break
        }
    }

    private fun doFlush(target: Sink, flushType: Int) {
        val outputChunk = ByteArray(bufferChunkSize)
        val targetByteCount = bufferChunkSize
        var targetWrite = 0
        var deflateResult = -1
        while (true) {
            var reachOutputLimit = false
            outputChunk.asUByteArray().usePinned { pinnedOutput ->
                zStream.next_in = null
                zStream.avail_in = 0u

                zStream.next_out = pinnedOutput.addressOf(0)
                zStream.avail_out = bufferChunkSize.toUInt()

                deflateResult = deflate(zStream.ptr, flushType)
                check(deflateResult != Z_STREAM_ERROR)

                targetWrite = targetByteCount - zStream.avail_out.toInt()
                if (zStream.avail_out.toInt() == 0) reachOutputLimit = true
            }
            target.write(outputChunk, 0, targetWrite)

            when (deflateResult) {
                Z_STREAM_END -> {
                    finished = true
                }

                else -> if (reachOutputLimit) continue
            }

            break
        }
    }
}