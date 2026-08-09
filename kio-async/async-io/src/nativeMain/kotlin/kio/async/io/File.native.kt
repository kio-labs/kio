@file:OptIn(ExperimentalForeignApi::class)

package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
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
import platform.posix.O_RDWR
import platform.posix.errno
import platform.posix.open
import platform.posix.strerror

actual suspend fun openFileSource(path: String): AsyncRawSource {
    val poller = currentCoroutineContext().poller
    val suspendIo = poller as SuspendIo

    val fd = openFile(path)

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

actual suspend fun openFileSink(path: String): AsyncRawSink {
    val poller = currentCoroutineContext().poller
    val suspendIo = poller as SuspendIo

    val fd = openFile(path, isReadOnly = false)
    setNonBlocking(fd)
    poller.attach(fd, POLL_INTEREST_WRITE)

    val sink = suspendIo.asyncRawSink(fd)
    return object : AsyncRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            return sink.write(source, byteCount)
        }

        override suspend fun flush() {
            sink.flush()
        }

        override suspend fun close() {
            poller.detach(fd, POLL_INTEREST_WRITE)
            sink.close()
        }
    }
}

private fun openFile(path: String, isReadOnly: Boolean = true): Int {
    while (true) {
        val fd = open(
            path,
            (if (isReadOnly) O_RDONLY else O_RDWR) or O_CLOEXEC
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
