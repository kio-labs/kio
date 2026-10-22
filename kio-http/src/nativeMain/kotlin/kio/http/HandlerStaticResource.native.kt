package kio.http

import kio.async.*
import kio.async.io.asyncRawSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import platform.posix.*

internal actual suspend fun openFileSource(path: String): AsyncRawSource {
    val io = currentCoroutineContext().poller.io
    val fd = io.openFile(path)

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