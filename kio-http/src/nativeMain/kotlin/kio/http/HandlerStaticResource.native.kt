package kio.http

import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.SuspendIo
import kio.async.attachFD
import kio.async.detachFD
import kio.async.io.asyncRawSource
import kio.async.open
import kio.async.poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.strerror

internal actual suspend fun openFileSource(path: String): AsyncRawSource {
    val io = currentCoroutineContext().poller.io
    val fd = io.openFile(path)

    setNonBlocking(fd)
    io.attachFD(fd, POLL_INTEREST_READ)

    val source = io.asyncRawSource(fd)
    return object : AsyncRawSource {
        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long
        ): Long = source.readAtMostTo(sink, byteCount)

        override suspend fun close() {
            io.detachFD(fd, POLL_INTEREST_READ)

            source.close()
        }
    }
}

private fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

@OptIn(ExperimentalForeignApi::class)
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