@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVarOf
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import platform.posix.sockaddr
import platform.posix.sockaddr_in

actual interface SuspendIo {
    fun attach(fd: Int, event: PollInterest) {}
    fun detach(fd: Int, event: PollInterest) {}

    suspend fun suspendWrite(fd: Int, buf: CPointer<*>, byte: ULong): Long
    suspend fun suspendRead(fd: Int, bytes: CPointer<*>, nbyte: ULong): Long
    suspend fun suspendAccept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int
    @Throws(IOException::class, CancellationException::class)
    suspend fun suspendConnect(fd: Int, addr: CPointer<sockaddr>, len: UInt)
}
