@file:OptIn(ExperimentalForeignApi::class)

package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SuspendIo
import kio.async.accept
import kio.async.attachFD
import kio.async.close
import kio.async.connect
import kio.async.detachFD
import kio.async.getsockname
import kio.async.poller
import kio.async.shutdown
import kio.async.socket
import kotlinx.atomicfu.atomic
import platform.posix.*
import kotlinx.cinterop.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlin.Int

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openConnection(host: String, port: Int): AsyncRawConnection = memScoped {
    val poller = currentCoroutineContext().poller
    val io = poller.io
    val hints = alloc<addrinfo> {
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_STREAM
        ai_protocol = IPPROTO_TCP
        ai_flags = 0
    }

    val result = allocPointerTo<addrinfo>()

    val rc = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
    if (rc != 0) {
        freeaddrinfo(result.value)
        throw IOException("getaddrinfo failed: ${gai_strerror(rc)?.toKString()}")
    }

    try {
        var lastError: Throwable? = null
        var ai = result.value

        while (ai != null) {
            val fd = io.socket(ai.pointed.ai_family, ai.pointed.ai_socktype, ai.pointed.ai_protocol)
            if (fd < 0) {
                lastError = IOException(resultErrorMessage(fd))
                ai = ai.pointed.ai_next
                continue
            }

            try {
                setNonBlocking(fd).let { ret ->
                    if (ret < 0) {
                        throw IOException("could not set socket non-blocking: ${resultErrorMessage(ret)}")
                    }
                }

                val aiAddr = ai.pointed.ai_addr ?: throw IOException("no ai_addr value")
                io.attachFD(fd, POLL_INTEREST_WRITE)
                io.connect(fd, aiAddr, ai.pointed.ai_addrlen)

                return@memScoped FdRawAsyncConnection(io = io, fd = fd)
            } catch (t: Throwable) {
                lastError = t
                io.detachFD(fd, POLL_INTEREST_WRITE)
                io.close(fd)
                ai = ai.pointed.ai_next
            }
        }

        throw IOException(lastError)
    } finally {
        freeaddrinfo(result.value)
    }
}

fun SuspendIo.serverSocket(serverFd: Int): ServerSocket = FdServerSocket(this, serverFd)

private class FdServerSocket(
    private val io: SuspendIo,
    private val serverFd: Int,
) : ServerSocket {
    init {
        io.attachFD(serverFd, POLL_INTEREST_READ)
    }

    override suspend fun getBoundPort(): Result<Int> {
        return getBoundPort(io, serverFd)
    }

    override suspend fun accept(): AsyncRawConnection = memScoped {
        val clientAddr = alloc<sockaddr_in> {}
        val clientAddrLen = alloc<UIntVar> { value = sizeOf<sockaddr_in>().convert() }

        val clientFd =
            io.accept(serverFd, clientAddr.ptr, clientAddrLen.ptr)

        if (clientFd < 0) {
            throw IOException("ERROR: could not accept connection from client: ${resultErrorMessage(clientFd)}")
        }

        try {
            if (setNonBlocking(clientFd) < 0) {
                throw IOException("ERROR: could not set client socket non-blocking.")
            }

            FdRawAsyncConnection(io, clientFd)
        } catch (t: Throwable) {
            io.close(clientFd)
            throw t
        }
    }

    override suspend fun close() {
        io.detachFD(serverFd, POLL_INTEREST_READ)
        io.close(serverFd)
    }
}

private class FdRawAsyncConnection(
    private val io: SuspendIo,
    private val fd: Int,
    override val source: AsyncRawSource = io.asyncRawSource(fd),
    override val sink: AsyncRawSink = io.asyncRawSink(fd)
) : AsyncRawConnection {

    private val closed = atomic(false)

    override suspend fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return

        io.shutdown(fd, SHUT_WR)

        try {
            // drain source buffer
            val buf = Buffer()
            while (true) {
                val read = source.readAtMostTo(buf, 1024)
                if (read == -1L) break
            }
        } catch (e: IOException) {
            // Ignore exception when close.
        } finally {
            io.close(fd)
        }
    }
}

internal fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

private suspend fun getBoundPort(io: SuspendIo, fd: Int): Result<Int> = memScoped {
    val addr = alloc<sockaddr_in>()
    val addrLen = alloc<socklen_tVar>().apply {
        value = sizeOf<sockaddr_in>().convert()
    }

    val ret = io.getsockname(fd, addr.ptr.reinterpret(), addrLen.ptr)
    if (ret == 0) {
        Result.success(ntohs(addr.sin_port).toInt())
    } else {
        Result.failure(IOException("getsockname failed: ${resultErrorMessage(ret)}"))
    }
}

private fun ntohs(value: UShort): UShort {
    val v = value.toUInt()
    return (((v and 0x00FFu) shl 8) or
            ((v and 0xFF00u) shr 8)).toUShort()
}
