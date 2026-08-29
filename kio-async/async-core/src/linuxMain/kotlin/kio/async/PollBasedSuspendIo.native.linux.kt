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
import kotlinx.io.IOException
import linux.platform.statx
import platform.posix.EINPROGRESS
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.errno
import platform.posix.sockaddr

interface PollBasedSuspendIo : SuspendIo, IoPoller {
    override suspend fun suspendWrite(fd: Int, buf: CPointer<*>, byte: ULong): Long {
        awaitIo(fd, POLL_INTEREST_WRITE)
        return platform.posix.write(fd, buf, byte)
    }

    override suspend fun suspendRead(fd: Int, bytes: CPointer<*>, nbyte: ULong): Long {
        awaitIo(fd, POLL_INTEREST_READ)
        return platform.posix.read(fd, bytes, nbyte)
    }

    override suspend fun suspendAccept(
        fd: Int,
        addr: CPointer<platform.posix.sockaddr_in>,
        addrLen: CPointer<UIntVarOf<UInt>>
    ): Int {
        awaitIo(fd, POLL_INTEREST_READ)
        return platform.posix.accept(fd, addr.reinterpret(), addrLen)
    }

    override suspend fun suspendConnect(fd: Int, addr: CPointer<platform.posix.sockaddr>, len: UInt) {
        val ret = platform.posix.connect(fd, addr, len)

        if (ret == 0) {
            return
        }

        if (errno != EINPROGRESS) {
            throw IOException("connect failed: ${errnoMessage()}")
        }

        awaitIo(fd, POLL_INTEREST_WRITE)

        val socketError = getSocketError(fd)
        if (socketError == 0) {
            return
        }

        throw IOException("connect failed: ${platform.posix.strerror(socketError)?.toKString() ?: "errno=$socketError"}")
    }

    override suspend fun suspendOpen(path: String?, flags: Int, mode: UInt): Int {
        return platform.posix.open(path, flags, mode)
    }

    override suspend fun suspendClose(fd: Int): Int {
        return platform.posix.close(fd)
    }

    override suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?, pipeFlags: Int): Int {
        // TODO: replace with linux api: pipe2
        return platform.posix.pipe(fds)
    }

    override suspend fun suspendStatx(dirfd: Int, path: String?, flags: Int, mask: UInt, buf: CPointer<statx>?): Int {
        // TODO: replace with linux api: statx
//        return statx(dirfd, path, flags, mask, buf)
        TODO("suspendStatx not implemented")
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

internal fun errnoMessage() = platform.posix.strerror(errno)?.toKString() ?: "Unknown errno: $errno"
