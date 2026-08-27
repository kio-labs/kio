package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.attachFD
import kio.async.close
import kio.async.detachFD
import kio.async.poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.currentCoroutineContext
import platform.posix.pipe

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openPipe(): AsyncRawConnection = memScoped {
    val io = currentCoroutineContext().poller.io
    val fds = allocArray<IntVar>(2)
    check(pipe(fds) == 0)

    val readFd: Int = fds[0]
    val writeFd: Int = fds[1]

    setNonBlocking(readFd)
    setNonBlocking(writeFd)

    io.attachFD(readFd, POLL_INTEREST_READ)
    io.attachFD(writeFd, POLL_INTEREST_WRITE)

    return@memScoped object : AsyncRawConnection {
        override val source: AsyncRawSource =
            io.asyncRawSource(readFd)
        override val sink: AsyncRawSink =
            io.asyncRawSink(writeFd)

        private var closed = false

        override suspend fun close() {
            if (closed) return
            closed = true

            io.detachFD(readFd, POLL_INTEREST_READ)
            io.detachFD(writeFd, POLL_INTEREST_WRITE)

            io.close(readFd)
            io.close(writeFd)
        }
    }
}
