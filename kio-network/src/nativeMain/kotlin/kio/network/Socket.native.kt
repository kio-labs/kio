package kio.network

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.asyncFdRawSink
import kio.async.asyncFdRawSource
import kio.async.awaitReadIo
import kio.async.awaitWriteIo
import platform.posix.*
import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.IOException
import platform.darwin.inet_addr
import kotlin.Int

@OptIn(ExperimentalForeignApi::class)
actual suspend fun openConnection(host: String, port: Int): AsyncConnection = memScoped {
    val hints = alloc<addrinfo> {
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_STREAM
        ai_protocol = IPPROTO_TCP
        ai_flags = 0
    }

    val result = allocPointerTo<addrinfo>()

    val rc = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
    if (rc != 0) {
        throw IOException("getaddrinfo failed: ${gai_strerror(rc)?.toKString()}")
    }

    try {
        var lastError: String? = null
        var ai = result.value

        while (ai != null) {
            val fd = socket(ai.pointed.ai_family, ai.pointed.ai_socktype, ai.pointed.ai_protocol)
            if (fd < 0) {
                lastError = errnoMessage()
                ai = ai.pointed.ai_next
                continue
            }

            try {
                if (setNonBlocking(fd) < 0) {
                    throw IOException("could not set socket non-blocking: ${errnoMessage()}")
                }

                val ret = connect(
                    fd,
                    ai.pointed.ai_addr,
                    ai.pointed.ai_addrlen,
                )

                if (ret == 0) {
                    return@memScoped FdRawAsyncConnection(fd = fd).buffered()
                }

                if (errno == EINPROGRESS) {
                    awaitWriteIo(fd)

                    val socketError = getSocketError(fd)
                    if (socketError == 0) {
                        return@memScoped FdRawAsyncConnection(fd = fd).buffered()
                    }

                    lastError = strerror(socketError)?.toKString()
                } else {
                    lastError = errnoMessage()
                }
            } catch (e: Throwable) {
                close(fd)
                throw e
            }

            close(fd)
            ai = ai.pointed.ai_next
        }

        throw IOException("connect failed: ${lastError ?: "unknown error"}")
    } finally {
        freeaddrinfo(result.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun tcpBind(
    host: String,
    port: Int,
    backlog: Int = 128,
): ServerSocket = memScoped {
// TODO: Judge IP type form host.
    val serverFd = socket(AF_INET, SOCK_STREAM, 0)
    if (serverFd < 0) {
        throw IOException("could not create server socket: ${errnoMessage()}")
    }

    try {
        val yes = alloc<IntVar> { value = 1 }

        if (setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, yes.ptr, sizeOf<IntVar>().convert()) < 0) {
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

        FdServerSocket(serverFd)
    } catch (t: Throwable) {
        close(serverFd)
        throw t
    }
}

private class FdServerSocket(
    private val serverFd: Int,
): ServerSocket {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun accept(): AsyncRawConnection = memScoped {
        awaitReadIo(serverFd)

        val clientAddr = alloc<sockaddr_in> {}
        val clientAddrLen = alloc<UIntVar> { value = sizeOf<sockaddr_in>().convert() }
        val clientFd =
            accept(serverFd, clientAddr.ptr.reinterpret(), clientAddrLen.ptr.reinterpret())

        if (clientFd < 0) {
            throw IOException("ERROR: could not accept connection from client: ${errnoMessage()}")
        }

        try {
            if (setNonBlocking(clientFd) < 0) {
                throw IOException("ERROR: could not set client socket non-blocking: ${errnoMessage()}\n")
            }

            FdRawAsyncConnection(clientFd)
        } catch (t: Throwable) {
            close(clientFd)
            throw t
        }
    }
}

private class FdRawAsyncConnection(
    val fd: Int,
    override val source: AsyncRawSource = asyncFdRawSource(fd),
    override val sink: AsyncRawSink = asyncFdRawSink(fd)
) : AsyncRawConnection {
    override suspend fun close() {
        shutdown(fd, SHUT_WR)

        try {
            // drain source buffer
            val buf = Buffer()
            while (true) {
                val read = source.readAtMostTo(buf, 1024)
                if (read == -1L) break
            }
        } catch (t: Throwable) {
            // ignore exception because we are closing
        } finally {
            close(fd)
        }
    }
}

private fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

@OptIn(ExperimentalForeignApi::class)
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

private  fun htons(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

@OptIn(ExperimentalForeignApi::class)
private fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}
