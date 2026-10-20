package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kio.async.close

internal fun pipeConnection(io: SuspendIo, readFd: Int, writeFd: Int): AsyncRawConnection {
    setNonBlocking(readFd)
    setNonBlocking(writeFd)

    return object : AsyncRawConnection {
        override val source: AsyncRawSource =
            io.asyncRawSource(readFd)
        override val sink: AsyncRawSink =
            io.asyncRawSink(writeFd)

        private var closed = false

        override suspend fun close() {
            if (closed) return
            closed = true

            io.close(readFd)
            io.close(writeFd)
        }
    }
}
