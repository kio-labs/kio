@file:OptIn(ExperimentalForeignApi::class)

package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import platform.posix.*

fun SuspendIo.asyncRawSink(fd: Int): AsyncRawSink = DefaultAsyncRawSink(fd, this)
fun SuspendIo.asyncRawSource(fd: Int): AsyncRawSource = DefaultAsyncRawSource(fd, this)

private class DefaultAsyncRawSink(
    private val fd: Int,
    private val suspendIo: SuspendIo
) : AsyncRawSink {
    @OptIn(UnsafeIoApi::class)
    override suspend fun write(source: Buffer, byteCount: Long) {
        doWrite(fd, source, byteCount) { fd, buf, byte ->
            suspendIo.suspendWrite(fd, buf, byte)
        }
    }

    override suspend fun flush() {
        // No-op
    }

    override suspend fun close() {
        close(fd)
    }
}

@OptIn(UnsafeIoApi::class)
private inline fun doWrite(
    fd: Int,
    source: Buffer, byteCount: Long,
    nativeWrite: (fd: Int, buf: CValuesRef<*>?, byte: ULong) -> Long
) {
    checkOffsetAndCount(source.size, 0, byteCount)
    var remaining = byteCount
    var bytesWritten = 0L
    while (remaining > 0) {
        UnsafeBufferOperations.readFromHead(source) { data, pos, limit ->
            val toCopy = minOf(remaining, limit - pos).toInt()
            bytesWritten = data.usePinned {
                val bytes = it.addressOf(pos).reinterpret<uint8_tVar>()

                nativeWrite(fd, bytes, toCopy.convert())
            }
            0
        }

        if (bytesWritten < 0L) throw IOException(errnoMessage())
        if (bytesWritten == 0L) throw IOException("reached capacity")

        source.skip(bytesWritten)
        remaining -= bytesWritten
    }
}

private class DefaultAsyncRawSource(
    private val fd: Int,
    private val suspendIo: SuspendIo
) : AsyncRawSource {
    @OptIn(UnsafeIoApi::class)
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        return doRead(fd, sink, byteCount) { fd, bytes, nbytes ->
            suspendIo.suspendRead(fd, bytes, nbytes)
        }
    }

    override suspend fun close() {
        close(fd)
    }
}

@OptIn(UnsafeIoApi::class)
private inline fun doRead(
    fd: Int, sink: Buffer, byteCount: Long,
    nativeRead: (fd: Int, bytes: CValuesRef<*>?, nbyte: ULong) -> Long
): Long {
    if (byteCount == 0L) return 0L
    require(byteCount >= 0) { "byteCount ($byteCount) < 0" }

    val bytesRead = UnsafeBufferOperations.writeToTail(sink, 1) { data, pos, limit ->
        val maxToCopy = minOf(byteCount, limit - pos)
        val read = data.usePinned { ba ->
            val bytes = ba.addressOf(pos).reinterpret<uint8_tVar>()

            nativeRead(fd, bytes, maxToCopy.convert())
        }
        if (read < 0) throw IOException(errnoMessage())
        read.toInt()
    }

    if (bytesRead == 0) return -1
    return bytesRead.toLong()
}

private fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset < 0 || offset > size || size - offset < byteCount || byteCount < 0) {
        throw IllegalArgumentException(
            "offset ($offset) and byteCount ($byteCount) are not within the range [0..size($size))"
        )
    }
}

private fun minOf(a: Long, b: Int): Long = minOf(a, b.toLong())
