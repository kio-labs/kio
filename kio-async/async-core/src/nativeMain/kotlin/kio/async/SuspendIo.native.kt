@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.UIntVarOf
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import platform.posix.sockaddr
import platform.posix.sockaddr_in

actual interface IoPoller {
    fun attach(fd: Int, event: PollInterest) {}
    fun detach(fd: Int, event: PollInterest) {}

    suspend fun awaitIo(handle: Int, interest: PollInterest)
}

fun SuspendIo.attachFD(fd: Int, event: PollInterest) {
    (this as? IoPoller)?.attach(fd, event)
}

fun SuspendIo.detachFD(fd: Int, event: PollInterest)  {
    (this as? IoPoller)?.detach(fd, event)
}

expect suspend fun SuspendIo.write(fd: Int, buf: CPointer<*>, byte: ULong): Long
expect suspend fun SuspendIo.read(fd: Int, bytes: CPointer<*>, nbyte: ULong): Long
expect suspend fun SuspendIo.accept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int

@Throws(IOException::class, CancellationException::class)
expect suspend fun SuspendIo.connect(fd: Int, addr: CPointer<sockaddr>, len: UInt)
expect suspend fun SuspendIo.open(path: String?, flags: Int, mode: UInt): Int
expect suspend fun SuspendIo.close(fd: Int): Int
expect suspend fun SuspendIo.shutdown(fd: Int, how: Int): Int
expect suspend fun SuspendIo.bind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int
expect suspend fun SuspendIo.listen(fd: Int, backlog: Int): Int