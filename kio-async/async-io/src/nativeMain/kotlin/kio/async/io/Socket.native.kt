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
import kio.async.getpeername
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


actual suspend fun Socket.asAsyncConnection(): AsyncRawConnection {
    val io = currentCoroutineContext().poller.io
    return FdRawAsyncConnection(io, this)
}

fun SuspendIo.serverSocket(serverFd: Int): ServerSocket {
    attachFD(serverFd, POLL_INTEREST_READ)
    return serverFd
}

actual typealias ServerSocket = Int
actual typealias Socket = Int

actual suspend fun ServerSocket.accept(): Socket {
    val io = currentCoroutineContext().poller.io
    return io.accept(this, null, null)
}

actual suspend fun ServerSocket.close() {
    val io = currentCoroutineContext().poller.io
    io.detachFD(this, POLL_INTEREST_READ)
    io.close(this)
}

private class FdRawAsyncConnection(
    private val io: SuspendIo,
    private val fd: Int,
    override val source: AsyncRawSource = io.asyncRawSource(fd),
    override val sink: AsyncRawSink = io.asyncRawSink(fd)
) : AsyncRawConnection {
    init {
        io.attachFD(fd, POLL_INTEREST_WRITE)
        io.attachFD(fd, POLL_INTEREST_READ)
    }

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
            io.detachFD(fd, POLL_INTEREST_WRITE)
            io.detachFD(fd, POLL_INTEREST_READ)

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

actual class SocketAddress internal constructor(
    internal val value: CValue<sockaddr_storage>,
)

actual suspend fun ServerSocket.getAddress(): Result<SocketAddress> {
    val io = currentCoroutineContext().poller.io
    return io.getsockname(this)
}

actual suspend fun Socket.getLocalAddress(): Result<SocketAddress> {
    val io = currentCoroutineContext().poller.io
    return io.getsockname(this)
}

actual suspend fun Socket.getRemoteAddress(): Result<SocketAddress> {
    val io = currentCoroutineContext().poller.io
    return io.getpeername(this)
}

private suspend fun SuspendIo.getsockname(fd: Int) = memScoped {
    val io = this@getsockname
    val addr = alloc<sockaddr_storage>()
    val len = alloc<socklen_tVar>().apply {
        value = sizeOf<sockaddr_storage>().convert()
    }

    val ret = io.getsockname(fd, addr.ptr.reinterpret(), len.ptr)
    if (ret < 0) {
        return@memScoped Result.failure(IOException("getsockname failed: ${resultErrorMessage(ret)}"))
    }

    Result.success(SocketAddress(addr.readValue()))
}


private suspend fun SuspendIo.getpeername(fd: Int): Result<SocketAddress> = memScoped {
    val io = this@getpeername
    val addr = alloc<sockaddr_storage>()
    val len = alloc<socklen_tVar>().apply {
        value = sizeOf<sockaddr_storage>().convert()
    }

    val ret = io.getpeername(fd, addr.ptr.reinterpret(), len.ptr)
    if (ret < 0) {
        val error = errno
        return@memScoped Result.failure(IOException("getpeername failed: ${resultErrorMessage(-error)}"))
    }

    Result.success(SocketAddress(addr.readValue()))
}

@OptIn(UnsafeNumber::class)
actual val SocketAddress.port: Int
    get() = value.useContents {
        when (ss_family.toInt()) {
            AF_INET ->
                ntohs(ptr.reinterpret<sockaddr_in>().pointed.sin_port).toInt()

            AF_INET6 ->
                ntohs(ptr.reinterpret<sockaddr_in6>().pointed.sin6_port).toInt()

            else -> error("Socket address has no IP port: $ss_family")
        }
    }

private fun ntohs(value: UShort): UShort {
    val v = value.toUInt()
    return (((v and 0x00FFu) shl 8) or
            ((v and 0xFF00u) shr 8)).toUShort()
}

