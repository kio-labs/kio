/*
 * Copyright 2026, the kio-async project contributors
 * SPDX-License-Identifier: Zlib
 */
package kio.async

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.AsyncRawSink
import kotlinx.io.AsyncRawSource
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import platform.posix.*

fun fdRawSink(fd: Int): RawSink = FdRawSink(fd)
fun asyncFdRawSink(fd: Int): AsyncRawSink = AsyncFdRawSink(fd)
fun fdRawSource(fd: Int): RawSource = FdRawSource(fd)
fun asyncFdRawSource(fd: Int): AsyncRawSource = AsyncFdRawSource(fd)

private class FdRawSink(
    val fd: Int
) : RawSink {
    @OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
    override fun write(source: Buffer, byteCount: Long) {
        doWrite(fd, source, byteCount, ::blockingWrite)
    }

    override fun flush() {
        // No-op
    }

    override fun close() {
        close(fd)
    }
}

private class AsyncFdRawSink(
    private val fd: Int,
) : AsyncRawSink {
    @OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
    override suspend fun write(source: Buffer, byteCount: Long) {
        doWrite(fd, source, byteCount) { fd, buf, byte ->
            suspendWrite(fd, buf, byte)
        }
    }

    override suspend fun flush() {
        // No-op
    }

    override fun close() {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun blockingWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long {
    return write(fd, buf, byte)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long {
    awaitWriteIo(fd)
    return write(fd, buf, byte)
}

@OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
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

private class FdRawSource(
    val fd: Int
) : RawSource {
    @OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        return doRead(fd, sink, byteCount, ::blockingRead)
    }

    override fun close() {
        close(fd)
    }
}

private class AsyncFdRawSource(
    private val fd: Int,
) : AsyncRawSource {
    @OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
    override suspend fun asyncReadAtMostTo(sink: Buffer, byteCount: Long): Long {
        return doRead(fd, sink, byteCount) { fd, bytes, nbytes ->
            suspendRead(fd, bytes, nbytes)
        }
    }

    override fun close() {
        close(fd)
    }
}

@OptIn(UnsafeIoApi::class, ExperimentalForeignApi::class)
private inline fun doRead(
    fd: Int, sink: Buffer, byteCount: Long,
    nativeRead: (fd: Int, bytes: CValuesRef<*>?, nbyte: ULong) -> Long
): Long {
    if (byteCount == 0L) return 0L
    checkByteCount(byteCount)

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

@OptIn(ExperimentalForeignApi::class)
private suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long {
    awaitReadIo(fd)
    return read(fd, bytes, nbyte)
}

@OptIn(ExperimentalForeignApi::class)
private fun blockingRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long {
    return read(fd, bytes, nbyte)
}
