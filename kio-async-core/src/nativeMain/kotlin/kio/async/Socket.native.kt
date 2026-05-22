package kio.async

import kotlinx.io.AsyncSink
import kotlinx.io.AsyncSource
import kotlinx.io.buffered
import platform.posix.*
import kotlinx.cinterop.*
import kotlinx.io.IOException

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
                    return@memScoped FdBasedAsyncConnection(
                        source = asyncFdRawSource(fd).buffered(),
                        sink = asyncFdRawSink(fd).buffered(),
                        fd = fd,
                    )
                }

                if (errno == EINPROGRESS) {
                    awaitWriteIo(fd)

                    val socketError = getSocketError(fd)
                    if (socketError == 0) {
                        return@memScoped FdBasedAsyncConnection(
                            source = asyncFdRawSource(fd).buffered(),
                            sink = asyncFdRawSink(fd).buffered(),
                            fd = fd,
                        )
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

fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

class FdBasedAsyncConnection(
    val fd: Int,
    override val source: AsyncSource = asyncFdRawSource(fd).buffered(),
    override val sink: AsyncSink = asyncFdRawSink(fd).buffered()
) : AsyncConnection {
    override suspend fun close() {
        shutdown(fd, SHUT_WR)

        try {
            // drain source buffer
            val buf = ByteArray(1024)
            while (!source.exhausted()) {
                val read = source.readAtMostTo(buf)
                if (read == -1) break
            }
        } catch (t: Throwable) {
            // ignore exception because we are closing
        } finally {
            platform.posix.close(fd)
        }
    }
}