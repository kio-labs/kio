@file:OptIn(ExperimentalForeignApi::class)

package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SuspendIo
import kio.async.attachFD
import kio.async.detachFD
import kio.async.open
import kio.async.poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.errno
import platform.posix.strerror

actual suspend fun openFileSource(path: String): AsyncRawSource {
    val poller = currentCoroutineContext().poller
    val suspendIo = poller as SuspendIo

    val fd = suspendIo.openFile(path)

    setNonBlocking(fd)
    poller.attachFD(fd, POLL_INTEREST_READ)

    val source = suspendIo.asyncRawSource(fd)
    return object : AsyncRawSource {
        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long
        ): Long = source.readAtMostTo(sink, byteCount)

        override suspend fun close() {
            poller.detachFD(fd, POLL_INTEREST_READ)

            source.close()
        }
    }
}

actual suspend fun openFileSink(path: String): AsyncRawSink {
    val poller = currentCoroutineContext().poller
    val suspendIo = poller as SuspendIo

    val fd = suspendIo.openFile(path, isReadOnly = false)
    setNonBlocking(fd)
    poller.attachFD(fd, POLL_INTEREST_WRITE)

    val sink = suspendIo.asyncRawSink(fd)
    return object : AsyncRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            return sink.write(source, byteCount)
        }

        override suspend fun flush() {
            sink.flush()
        }

        override suspend fun close() {
            poller.detachFD(fd, POLL_INTEREST_WRITE)
            sink.close()
        }
    }
}

private suspend fun SuspendIo.openFile(path: String, isReadOnly: Boolean = true): Int {
    while (true) {
        val flags = if (isReadOnly) {
            O_RDONLY or O_CLOEXEC
        } else {
            O_WRONLY or O_CREAT or O_TRUNC or O_CLOEXEC
        }

        val fd = if (isReadOnly) {
            open(path, flags, 0.toUInt())
        } else {
            open(path, flags, 0x1A4.toUInt()) // 0644
        }

        if (fd >= 0) {
            return fd
        }

        val error = errno
        if (error == EINTR) continue

        throw IOException(
            "open failed for $path: " +
                    (strerror(error)?.toKString() ?: "errno=$error")
        )
    }
}
