@file:OptIn(ExperimentalForeignApi::class)

package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.Poller
import kio.async.poller
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
            val fd = socket(ai.pointed.ai_family, ai.pointed.ai_socktype, ai.pointed.ai_protocol)
            if (fd < 0) {
                lastError = IOException(errnoMessage())
                ai = ai.pointed.ai_next
                continue
            }

            try {
                if (setNonBlocking(fd) < 0) {
                    throw IOException("could not set socket non-blocking: ${errnoMessage()}")
                }

                val aiAddr = ai.pointed.ai_addr ?: throw IOException("no ai_addr value")
                poller.attach(fd, POLL_INTEREST_WRITE)
                poller.suspendConnect(fd, aiAddr, ai.pointed.ai_addrlen)

                return@memScoped FdRawAsyncConnection(poller = poller, fd = fd)
            } catch (t: Throwable) {
                lastError = t
                poller.detach(fd, POLL_INTEREST_WRITE)
                close(fd)
                ai = ai.pointed.ai_next
            }
        }

        throw IOException(lastError)
    } finally {
        freeaddrinfo(result.value)
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual suspend fun tcpBind(host: String, port: Int): ServerSocket = memScoped {
    val poller = currentCoroutineContext().poller
    val backlog: Int = 128
// TODO: Judge IP type form host.
    val serverFd = socket(AF_INET, SOCK_STREAM, 0)
    if (serverFd < 0) {
        throw IOException("could not create server socket: ${errnoMessage()}")
    }

    try {
        val yes = alloc<IntVar> { value = 1 }

        if (setsockopt(
                serverFd,
                SOL_SOCKET,
                SO_REUSEADDR,
                yes.ptr,
                sizeOf<IntVar>().convert()
            ) < 0
        ) {
            throw IOException("could not configure SO_REUSEADDR: ${errnoMessage()}")
        }

        if (setNonBlocking(serverFd) < 0) {
            throw IOException("could not set server socket non-blocking: ${errnoMessage()}")
        }

        val ip = inet_addr(host)
        if (ip == INADDR_NONE) {
            throw IOException("invalid IPv4 address: $host")
        }

        val serverAddr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = htons(port.convert())
            sin_addr.s_addr = ip
        }

        if (bind(serverFd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) < 0) {
            throw IOException("could not bind $host:$port: ${errnoMessage()}")
        }

        if (listen(serverFd, backlog) < 0) {
            throw IOException("could not listen $host:$port: ${errnoMessage()}")
        }

        FdServerSocket(poller, serverFd)
    } catch (t: Throwable) {
        close(serverFd)
        throw t
    }
}

internal expect fun inet_addr(host: String?): UInt

private class FdServerSocket(
    private val poller: Poller,
    private val serverFd: Int,
) : ServerSocket {
    init {
        poller.attach(serverFd, POLL_INTEREST_READ)
    }

    override val boundPort: Int by lazy {
        getBoundPort(serverFd)
    }

    override suspend fun accept(): AsyncRawConnection = memScoped {
        val clientAddr = alloc<sockaddr_in> {}
        val clientAddrLen = alloc<UIntVar> { value = sizeOf<sockaddr_in>().convert() }

        val clientFd =
            poller.suspendAccept(serverFd, clientAddr.ptr, clientAddrLen.ptr)

        if (clientFd < 0) {
            throw IOException("ERROR: could not accept connection from client: ${errnoMessage()}")
        }

        try {
            if (setNonBlocking(clientFd) < 0) {
                throw IOException("ERROR: could not set client socket non-blocking: ${errnoMessage()}\n")
            }

            FdRawAsyncConnection(poller, clientFd)
        } catch (t: Throwable) {
            close(clientFd)
            throw t
        }
    }

    override fun close() {
        poller.detach(serverFd, POLL_INTEREST_READ)
    }
}

private class FdRawAsyncConnection(
    private val poller: Poller,
    private val fd: Int,
    override val source: AsyncRawSource = poller.asyncRawSource(fd),
    override val sink: AsyncRawSink = poller.asyncRawSink(fd)
) : AsyncRawConnection {
    init {
        poller.attach(fd, POLL_INTEREST_WRITE)
        poller.attach(fd, POLL_INTEREST_READ)
    }

    private val closed = atomic(false)

    override suspend fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return

        shutdown(fd, SHUT_WR)

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
            poller.detach(fd, POLL_INTEREST_WRITE)
            poller.detach(fd, POLL_INTEREST_READ)

            close(fd)
        }
    }
}

internal fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

private fun getBoundPort(fd: Int): Int = memScoped {
    val addr = alloc<sockaddr_in>()
    val addrLen = alloc<socklen_tVar>().apply {
        value = sizeOf<sockaddr_in>().convert()
    }

    check(
        getsockname(
            fd,
            addr.ptr.reinterpret(),
            addrLen.ptr
        ) == 0
    )

    ntohs(addr.sin_port).toInt()
}

private fun ntohs(value: UShort): UShort {
    val v = value.toUInt()
    return (((v and 0x00FFu) shl 8) or
            ((v and 0xFF00u) shr 8)).toUShort()
}

private fun htons(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}
