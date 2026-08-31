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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.EINPROGRESS
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.errno
import platform.posix.sockaddr
import platform.posix.strerror

interface PollBasedSuspendIo : SuspendIo, IoPoller {
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
        func ={ platform.posix.accept(fd, addr.reinterpret(), addrLen) },
        waitIO = { awaitIo(fd, POLL_INTEREST_READ) }
    )

    override suspend fun suspendConnect(fd: Int, addr: CPointer<platform.posix.sockaddr>, len: UInt): Int {
        val ret = platform.posix.connect(fd, addr, len)

        if (ret == 0) {
            return 0
        }

        val connectErrno = errno

        if (connectErrno != EINPROGRESS) {
            return connectErrno
        }

        awaitIo(fd, POLL_INTEREST_WRITE)

        return getSocketError(fd)
    }

    override suspend fun suspendOpen(path: String?, flags: Int, mode: UInt): Int {
        return platform.posix.open(path, flags, mode)
    }

    override suspend fun suspendClose(fd: Int): Int {
        return platform.posix.close(fd)
    }

    override suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?): Int {
        return platform.posix.pipe(fds)
    }

    override suspend fun suspendStat(path: String?, buf: CPointer<platform.posix.stat>?): Int {
        return platform.posix.stat(path, buf)
    }

    override suspend fun suspendShutdown(fd: Int, how: Int): Int {
        return platform.posix.shutdown(fd, how)
    }

    override suspend fun suspendBind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int {
        return platform.posix.bind(fd, addr, addrlen)
    }

    override suspend fun suspendListen(fd: Int, backlog: Int): Int {
        return platform.posix.listen(fd, backlog)
    }

    override suspend fun suspendSocket(domain: Int, type: Int, protocol: Int): Int {
        return platform.posix.socket(domain, type, protocol)
    }

    override suspend fun suspendGetsockname(fd: Int, addr: CPointer<sockaddr>?, len: CPointer<UIntVarOf<UInt>>?): Int {
        return platform.posix.getsockname(fd, addr, len)
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

internal fun errnoMessage() = strerror(errno)?.toKString() ?: "Unknown errno: $errno"
