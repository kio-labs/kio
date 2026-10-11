package kio.compression

import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kio.async.close
import kio.async.pread
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.strerror
import platform.posix.uint8_tVar

internal actual suspend fun SuspendIo.positionalFileSource(
    initialOffset: Long,
    fd: FileHandle
): AsyncRawSource {
    return PositionalFileSource(initialOffset, fd, this)
}

private class PositionalFileSource(
    initialOffset: Long,
    private val fd: Int,
    private val io: SuspendIo
) : AsyncRawSource {
    private var position: Long = initialOffset

    @OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        require(byteCount >= 0) { "byteCount ($byteCount) < 0" }

        val bytesRead = UnsafeBufferOperations.writeToTail(sink, 1) { data, pos, limit ->
            val maxToCopy = minOf(byteCount, (limit - pos).toLong())
            val read = data.usePinned { ba ->
                val bytes = ba.addressOf(pos).reinterpret<uint8_tVar>()

                io.pread(fd, bytes, maxToCopy.convert(), position).toLong()
            }
            if (read < 0) throw IOException("failed to do pread, fd=$fd: ${strerror(-read.toInt())?.toKString()}")
            read.toInt()
        }

        position += bytesRead

        if (bytesRead == 0) return -1
        return bytesRead.toLong()
    }

    override suspend fun close() {
        io.close(fd)
    }
}

actual typealias FileHandle = Int