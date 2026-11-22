package kio.async.io

import kio.async.fdRegistration
import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.Poller
import kio.async.posix

internal fun pipeConnection(poller: Poller, readFd: Int, writeFd: Int): AsyncRawConnection {
    val io = poller.io
    io.fdRegistration?.attachRead(readFd)
    io.fdRegistration?.attachWrite(writeFd)

    return object : AsyncRawConnection {
        override val source: AsyncRawSource =
            io.asyncRawSource(readFd)
        override val sink: AsyncRawSink =
            io.asyncRawSink(writeFd)

        private var closed = false

        override suspend fun close() {
            if (closed) return
            closed = true

            io.fdRegistration?.detachRead(readFd)
            io.fdRegistration?.detachWrite(writeFd)

            io.posix.close(readFd)
            io.posix.close(writeFd)
        }
    }
}
