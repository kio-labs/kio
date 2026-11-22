@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.UIntVarOf
import kotlinx.cinterop.UnsafeNumber
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
import platform.posix.msghdr
import platform.posix.sockaddr
import platform.posix.sockaddr_in

/**
 * io_uring compatible result semantics:
 *
 * >= 0 : success
 * < 0  : -errno
 */
interface PosixApi {
    suspend fun write(fd: Int, buf: CPointer<*>, byte: ULong): Int = platform.posix.write(fd, buf, byte).toInt().negErrno()
    suspend fun read(fd: Int, bytes: CPointer<*>, nbyte: ULong): Int = platform.posix.read(fd, bytes, nbyte).toInt().negErrno()
    suspend fun accept(fd: Int, addr: CPointer<sockaddr_in>?, addrLen: CPointer<UIntVarOf<UInt>>?): Int = platform.posix.accept(fd, addr?.reinterpret(), addrLen).negErrno()
    suspend fun connect(fd: Int, addr: CPointer<sockaddr>, len: UInt): Int = platform.posix.connect(fd, addr, len).negErrno()
    suspend fun pread(fd: Int, bytes: CPointer<*>?, nbyte: ULong, offset: Long): Int = platform.posix.pread(fd, bytes, nbyte, offset).toInt().negErrno()
    suspend fun open(path: String?, flags: Int, mode: UInt): Int = platform.posix.open(path, flags, mode).negErrno()
    suspend fun close(fd: Int): Int = platform.posix.close(fd).negErrno()
    suspend fun pipe(fds: CPointer<IntVarOf<Int>>?): Int = platform.posix.pipe(fds).negErrno()
    suspend fun shutdown(fd: Int, how: Int): Int = platform.posix.shutdown(fd, how).negErrno()
    suspend fun bind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int = platform.posix.bind(fd, addr, addrlen).negErrno()
    suspend fun listen(fd: Int, backlog: Int): Int = platform.posix.listen(fd, backlog).negErrno()
    suspend fun socket(domain: Int, type: Int, protocol: Int): Int = platform.posix.socket(domain, type, protocol).negErrno()
    suspend fun stat(path: String?, buf: CPointer<platform.posix.stat>?): Int = platform.posix.stat(path, buf).negErrno()
    suspend fun fstat(fd: Int, buf: CPointer<platform.posix.stat>?): Int = platform.posix.fstat(fd, buf)
    suspend fun getsockname(fd: Int, addr: CPointer<sockaddr>?, len: CPointer<UIntVarOf<UInt>>?): Int = platform.posix.getsockname(fd, addr, len).negErrno()
    suspend fun getpeername(fd: Int, addr: CPointer<sockaddr>?, len: CPointer<UIntVarOf<UInt>>?): Int = platform.posix.getpeername(fd, addr, len).negErrno()
    @OptIn(UnsafeNumber::class)
    suspend fun mkdir(path: String?, mode: UInt): Int = platform.posix.mkdir(path, mode.convert()).negErrno()
    suspend fun recvmsg(fd: Int, message: CPointer<msghdr>?, flags: Int): Int = platform.posix.recvmsg(fd, message, flags).toInt().negErrno()
    suspend fun sendmsg(fd: Int, message: CPointer<msghdr>?, flags: Int): Int = platform.posix.sendmsg(fd, message, flags).toInt().negErrno()
    suspend fun sendto(fd: Int, buf: CPointer<*>?, n: ULong, flags: Int, addr: CPointer<sockaddr>?, addrLen: UInt): Int = platform.posix.sendto(fd, buf, n, flags, addr, addrLen).toInt().negErrno()
    suspend fun waitid(idtype: platform.posix.idtype_t, id: UInt, infop: CPointer<platform.posix.siginfo_t>?, options: Int): Int = platform.posix.waitid(idtype, id, infop, options).negErrno()
}

val SuspendIo.posix: PosixApi
    get() = this as PosixApi

val SuspendIo.fdRegistration: FdRegistration?
    get() = this as? FdRegistration

interface FdRegistration {
    fun attachRead(fd: Int)
    fun attachWrite(fd: Int)
    fun detachRead(fd: Int)
    fun detachWrite(fd: Int)
}

abstract class PosixSuspendIo: PosixApi {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun write(fd: Int, buf: CPointer<*>, byte: ULong): Int = posixCall(
        func = { platform.posix.write(fd, buf, byte).toInt() },
        waitIO = { awaitForWrite(fd) }
    )

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun sendmsg(fd: Int, message: CPointer<msghdr>?, flags: Int): Int = posixCall(
        func = { platform.posix.sendmsg(fd, message, flags).toInt() },
        waitIO = { awaitForWrite(fd) }
    )

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun sendto(fd: Int, buf: CPointer<*>?, n: ULong, flags: Int, addr: CPointer<sockaddr>?, addrLen: UInt): Int = posixCall(
        func = { platform.posix.sendto(fd, buf, n, flags, addr, addrLen).toInt() },
        waitIO = { awaitForWrite(fd) }
    )

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun read(fd: Int, bytes: CPointer<*>, nbyte: ULong): Int = posixCall(
        func = { platform.posix.read(fd, bytes, nbyte).toInt() },
        waitIO = { awaitForRead(fd) }
    )

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun recvmsg(fd: Int, message: CPointer<msghdr>?, flags: Int): Int = posixCall(
        func = { platform.posix.recvmsg(fd, message, flags).toInt() },
        waitIO = { awaitForRead(fd) }
    )

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun accept(
        fd: Int,
        addr: CPointer<platform.posix.sockaddr_in>?,
        addrLen: CPointer<UIntVarOf<UInt>>?
    ): Int = posixCall(
        func = { platform.posix.accept(fd, addr?.reinterpret(), addrLen) },
        waitIO = { awaitForRead(fd) }
    )


    @OptIn(ExperimentalForeignApi::class)
    override suspend fun connect(fd: Int, addr: CPointer<platform.posix.sockaddr>, len: UInt): Int {
        val ret = platform.posix.connect(fd, addr, len)
        if (ret == 0) {
            return 0
        }

        val connectErrno = errno

        if (connectErrno != EINPROGRESS) {
            return -connectErrno
        }

        awaitForWrite(fd)

        val socketError = getSocketError(fd)
        return if (socketError == 0) 0 else -socketError
    }

    abstract suspend fun awaitForRead(fd: Int)
    abstract suspend fun awaitForWrite(fd: Int)
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
