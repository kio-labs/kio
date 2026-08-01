@file:OptIn(ExperimentalForeignApi::class)

package kio.async.io

import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.SuspendIo
import kio.async.poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.errno
import platform.posix.open
import platform.posix.strerror

actual suspend fun openFileSource(path: String): AsyncRawSource {
    val poller = currentCoroutineContext().poller
    val suspendIo = poller as SuspendIo

    val fd = openReadOnly(path)

    setNonBlocking(fd)
    poller.attach(fd, POLL_INTEREST_READ)

    val source = suspendIo.asyncRawSource(fd)
    return object : AsyncRawSource {
        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long
        ): Long = source.readAtMostTo(sink, byteCount)

        override suspend fun close() {
            poller.detach(fd, POLL_INTEREST_READ)

            source.close()
        }
    }
}

private fun openReadOnly(path: String): Int {
    while (true) {
        val fd = open(
            path,
            O_RDONLY or O_CLOEXEC
        )

        if (fd >= 0) {
            return fd
        }

        val error = errno

        if (error == EINTR) {
            continue
        }

        throw IOException(
            "open failed for ${path}: " +
                    (strerror(error)?.toKString() ?: "errno=$error")
        )
    }
}