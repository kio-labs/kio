package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SuspendIo
import kio.async.attachFD
import kio.async.close
import kio.async.detachFD
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped

@OptIn(ExperimentalForeignApi::class)
internal fun pipeConnection(io: SuspendIo, readFd: Int, writeFd: Int): AsyncRawConnection = memScoped {
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
