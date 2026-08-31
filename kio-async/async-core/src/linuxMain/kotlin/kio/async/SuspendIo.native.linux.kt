@file:OptIn(ExperimentalForeignApi::class)
package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.UIntVarOf
import linux.platform.statx
import platform.posix.sockaddr
import platform.posix.sockaddr_in

interface LinuxApi {
    suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?, pipeFlags: Int): Int
    suspend fun suspendStatx(dirfd: Int, path: String?, flags: Int, mask: UInt, buf: CPointer<statx>?): Int
}

actual interface SuspendIo: PosixApi, LinuxApi

actual suspend fun SuspendIo.write(fd: Int, buf: CPointer<*>, byte: ULong): Int = suspendWrite(fd, buf, byte)
actual suspend fun SuspendIo.read(fd: Int, bytes: CPointer<*>, nbyte: ULong): Int = suspendRead(fd, bytes, nbyte)
actual suspend fun SuspendIo.accept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int = suspendAccept(fd, addr, addrLen)
actual suspend fun SuspendIo.connect(fd: Int, addr: CPointer<sockaddr>, len: UInt): Int = suspendConnect(fd, addr, len)
actual suspend fun SuspendIo.open(path: String?, flags: Int, mode: UInt): Int = suspendOpen(path, flags, mode)
actual suspend fun SuspendIo.close(fd: Int): Int = suspendClose(fd)
actual suspend fun SuspendIo.shutdown(fd: Int, how: Int): Int = suspendShutdown(fd, how)
actual suspend fun SuspendIo.bind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int = suspendBind(fd, addr, addrlen)
actual suspend fun SuspendIo.listen(fd: Int, backlog: Int): Int = suspendListen(fd, backlog)
actual suspend fun SuspendIo.socket(domain: Int, type: Int, protocol: Int): Int = suspendSocket(domain, type, protocol)

interface LinuxSuspendIo: LinuxApi {
    override suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?, pipeFlags: Int): Int {
        // TODO: replace with linux api: pipe2
        return platform.posix.pipe(fds)
    }

    override suspend fun suspendStatx(dirfd: Int, path: String?, flags: Int, mask: UInt, buf: CPointer<statx>?): Int {
        // TODO: replace with linux api: statx
//        return statx(dirfd, path, flags, mask, buf)
        TODO("suspendStatx not implemented")
    }
}