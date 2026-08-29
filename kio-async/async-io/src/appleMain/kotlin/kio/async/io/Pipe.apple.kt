package kio.async.io

import kio.async.poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped

@OptIn(ExperimentalForeignApi::class)
suspend fun pipe(): AsyncRawConnection = memScoped {
    val io = currentCoroutineContext().poller.io
    val fds = allocArray<IntVar>(2)
    check(io.suspendPipe(fds) == 0)

    val readFd: Int = fds[0]
    val writeFd: Int = fds[1]

    pipeConnection(io, readFd, writeFd)
}