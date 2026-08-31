@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.UIntVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.EINPROGRESS
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.errno
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.stat

/**
 * io_uring compatible result semantics:
 *
 * >= 0 : success
 * < 0  : -errno
 */
interface PosixApi {
    suspend fun suspendWrite(fd: Int, buf: CPointer<*>, byte: ULong): Int
    suspend fun suspendRead(fd: Int, bytes: CPointer<*>, nbyte: ULong): Int
    suspend fun suspendAccept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int

    suspend fun suspendConnect(fd: Int, addr: CPointer<sockaddr>, len: UInt): Int
    suspend fun suspendOpen(path: String?, flags: Int, mode: UInt): Int
    suspend fun suspendClose(fd: Int): Int

    suspend fun suspendShutdown(fd: Int, how: Int): Int
    suspend fun suspendBind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int
    suspend fun suspendListen(fd: Int, backlog: Int): Int
    suspend fun suspendSocket(domain: Int, type: Int, protocol: Int): Int

    suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?): Int
    suspend fun suspendStat(path: String?, buf: CPointer<stat>?): Int
}

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

expect suspend fun SuspendIo.write(fd: Int, buf: CPointer<*>, byte: ULong): Int
expect suspend fun SuspendIo.read(fd: Int, bytes: CPointer<*>, nbyte: ULong): Int
expect suspend fun SuspendIo.accept(fd: Int, addr: CPointer<sockaddr_in>, addrLen: CPointer<UIntVarOf<UInt>>): Int

expect suspend fun SuspendIo.connect(fd: Int, addr: CPointer<sockaddr>, len: UInt): Int
expect suspend fun SuspendIo.open(path: String?, flags: Int, mode: UInt): Int
expect suspend fun SuspendIo.close(fd: Int): Int
expect suspend fun SuspendIo.shutdown(fd: Int, how: Int): Int
expect suspend fun SuspendIo.bind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int
expect suspend fun SuspendIo.listen(fd: Int, backlog: Int): Int
expect suspend fun SuspendIo.socket(domain: Int, type: Int, protocol: Int): Int

interface PosixSuspendIo : PosixApi, IoPoller {
    override suspend fun suspendWrite(fd: Int, buf: CPointer<*>, byte: ULong): Int = posixCall(
        func = { platform.posix.write(fd, buf, byte).toInt() },
        waitIO = { awaitIo(fd, POLL_INTEREST_WRITE) }
    )

    override suspend fun suspendRead(fd: Int, bytes: CPointer<*>, nbyte: ULong): Int = posixCall(
        func = { platform.posix.read(fd, bytes, nbyte).toInt() },
        waitIO = { awaitIo(fd, POLL_INTEREST_READ) }
    )

    override suspend fun suspendAccept(
        fd: Int,
        addr: CPointer<platform.posix.sockaddr_in>,
        addrLen: CPointer<UIntVarOf<UInt>>
    ): Int = posixCall(
        func = { platform.posix.accept(fd, addr.reinterpret(), addrLen) },
        waitIO = { awaitIo(fd, POLL_INTEREST_READ) }
    )

    override suspend fun suspendConnect(fd: Int, addr: CPointer<platform.posix.sockaddr>, len: UInt): Int {
        val ret = platform.posix.connect(fd, addr, len)

        if (ret == 0) {
            return 0
        }

        val connectErrno = errno

        if (connectErrno != EINPROGRESS) {
            return -connectErrno
        }

        awaitIo(fd, POLL_INTEREST_WRITE)

        val socketError = getSocketError(fd)
        return if (socketError == 0) 0 else -socketError
    }

    override suspend fun suspendOpen(path: String?, flags: Int, mode: UInt): Int {
        return platform.posix.open(path, flags, mode).negErrno()
    }

    override suspend fun suspendClose(fd: Int): Int {
        return platform.posix.close(fd).negErrno()
    }

    override suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?): Int {
        return platform.posix.pipe(fds).negErrno()
    }

    override suspend fun suspendShutdown(fd: Int, how: Int): Int {
        return platform.posix.shutdown(fd, how).negErrno()
    }

    override suspend fun suspendBind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int {
        return platform.posix.bind(fd, addr, addrlen).negErrno()
    }

    override suspend fun suspendListen(fd: Int, backlog: Int): Int {
        return platform.posix.listen(fd, backlog).negErrno()
    }

    override suspend fun suspendSocket(domain: Int, type: Int, protocol: Int): Int {
        return platform.posix.socket(domain, type, protocol).negErrno()
    }

    override suspend fun suspendStat(path: String?, buf: CPointer<platform.posix.stat>?): Int {
        return platform.posix.stat(path, buf).negErrno()
    }
}

private fun getSocketError(fd: Int): Int = memScoped {
    val error = alloc<IntVar>()
    val len = alloc<platform.posix.socklen_tVar>()
    len.value = sizeOf<IntVar>().convert()

    val rc = platform.posix.getsockopt(
        fd,
        SOL_SOCKET,
        SO_ERROR,
        error.ptr,
        len.ptr,
    )

    if (rc < 0) errno else error.value
}
