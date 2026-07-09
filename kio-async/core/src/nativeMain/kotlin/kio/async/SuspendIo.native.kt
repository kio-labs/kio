@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.io.IOException
import platform.posix.EINPROGRESS
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.accept
import platform.posix.connect
import platform.posix.errno
import platform.posix.getsockopt
import platform.posix.read
import platform.posix.sockaddr
import platform.posix.socklen_tVar
import platform.posix.strerror
import platform.posix.write

actual interface SuspendIo {
    fun attach(fd: Int, event: PollInterest) {}
    fun detach(fd: Int, event: PollInterest) {}

    suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long
    suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long
    suspend fun suspendAccept(fd: Int, addr: CValuesRef<sockaddr>, addrLen: CValuesRef<UIntVarOf<UInt>>): Int
    suspend fun suspendConnect(fd: Int, addr: CValuesRef<sockaddr>?, len: UInt)
}

interface SuspendSyscallIo : SuspendIo {
    suspend fun awaitIo(handle: Int, interest: PollInterest)

    override suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long {
        awaitIo(fd, POLL_INTEREST_WRITE)
        return write(fd, buf, byte)
    }

    override suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long {
        awaitIo(fd, POLL_INTEREST_READ)
        return read(fd, bytes, nbyte)
    }

    override suspend fun suspendAccept(
        fd: Int,
        addr: CValuesRef<sockaddr>,
        addrLen: CValuesRef<UIntVarOf<UInt>>
    ): Int {
        awaitIo(fd, POLL_INTEREST_READ)
        return accept(fd, addr, addrLen)
    }

    override suspend fun suspendConnect(fd: Int, addr: CValuesRef<sockaddr>?, len: UInt) {
        val ret = connect(fd, addr, len)

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

        throw IOException("connect failed: ${strerror(socketError)?.toKString() ?: "errno=$socketError"}")
    }
}

private fun getSocketError(fd: Int): Int = memScoped {
    val error = alloc<IntVar>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<IntVar>().convert()

    val rc = getsockopt(
        fd,
        SOL_SOCKET,
        SO_ERROR,
        error.ptr,
        len.ptr,
    )

    if (rc < 0) errno else error.value
}

internal fun errnoMessage() = strerror(errno)?.toKString() ?: "Unknown errno: $errno"
