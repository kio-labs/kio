@file:OptIn(ExperimentalForeignApi::class)
package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.UIntVarOf
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import linux.platform.statx
import platform.posix.sockaddr
import platform.posix.sockaddr_in

actual interface SuspendIo {
    suspend fun suspendWrite(fd: Int, buf: CPointer<*>, byte: ULong): Long
    suspend fun suspendRead(fd: Int, bytes: CPointer<*>, nbyte: ULong): Long
    suspend fun suspendAccept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int

    @Throws(IOException::class, CancellationException::class)
    suspend fun suspendConnect(fd: Int, addr: CPointer<sockaddr>, len: UInt)
    suspend fun suspendOpen(path: String?, flags: Int, mode: UInt): Int
    suspend fun suspendClose(fd: Int): Int
    suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?, pipeFlags: Int): Int
    suspend fun suspendStatx(dirfd: Int, path: String?, flags: Int, mask: UInt, buf: CPointer<statx>?): Int
}

actual suspend fun SuspendIo.write(fd: Int, buf: CPointer<*>, byte: ULong): Long = suspendWrite(fd, buf, byte)
actual suspend fun SuspendIo.read(fd: Int, bytes: CPointer<*>, nbyte: ULong): Long = suspendRead(fd, bytes, nbyte)
actual suspend fun SuspendIo.accept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int = suspendAccept(fd, addr, addrLen)
@Throws(exceptionClasses = [IOException::class, kotlin.coroutines.cancellation.CancellationException::class])
actual suspend fun SuspendIo.connect(fd: Int, addr: CPointer<sockaddr>, len: UInt) = suspendConnect(fd, addr, len)
actual suspend fun SuspendIo.open(path: String?, flags: Int, mode: UInt): Int = suspendOpen(path, flags, mode)
actual suspend fun SuspendIo.close(fd: Int): Int = suspendClose(fd)
