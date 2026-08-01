package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SuspendIo
import kio.async.poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.currentCoroutineContext
import platform.posix.close
import platform.posix.pipe

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openPipe(): AsyncRawConnection = memScoped {
    val poller = currentCoroutineContext().poller
    val suspendIo = poller as SuspendIo
    val fds = allocArray<IntVar>(2)
    check(pipe(fds) == 0)

    val readFd: Int = fds[0]
    val writeFd: Int = fds[1]

    setNonBlocking(readFd)
    setNonBlocking(writeFd)

    poller.attach(readFd, POLL_INTEREST_READ)
    poller.attach(writeFd, POLL_INTEREST_WRITE)

    return@memScoped object : AsyncRawConnection {
        override val source: AsyncRawSource =
            suspendIo.asyncRawSource(readFd)
        override val sink: AsyncRawSink =
            suspendIo.asyncRawSink(writeFd)

        private var closed = false

        override suspend fun close() {
            if (closed) return
            closed = true

            poller.detach(readFd, POLL_INTEREST_READ)
            poller.detach(writeFd, POLL_INTEREST_WRITE)

            close(readFd)
            close(writeFd)
        }
    }
}
