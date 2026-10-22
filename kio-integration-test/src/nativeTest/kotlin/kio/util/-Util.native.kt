package kio.util

import kio.async.bind
import kio.async.close
import kio.async.io.ServerSocket
import kio.async.io.getAddress
import kio.async.io.port
import kio.async.io.serverSocket
import kio.async.listen
import kio.async.poller
import kio.async.socket
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.IOException
import platform.posix.AF_INET
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.INADDR_NONE
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.fcntl
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
actual suspend fun tcpBind(host: String, port: Int): ServerSocket = memScoped {
    val io = currentCoroutineContext().poller.io
    val backlog: Int = 128
// TODO: Judge IP type form host.
    val serverFd = io.socket(AF_INET, SOCK_STREAM, 0)
    if (serverFd < 0) {
        throw IOException("could not create server socket: ${resultErrorMessage(serverFd)}")
    }

    try {
        val yes = alloc<IntVar> { value = 1 }

        setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, yes.ptr, sizeOf<IntVar>().convert()).let { ret ->
            if (ret < 0) {
                throw IOException("could not configure SO_REUSEADDR: ${resultErrorMessage(ret)}")
            }
        }

        setNonBlocking(serverFd).let { ret ->
            if (ret < 0) {
                throw IOException("could not set server socket non-blocking: ${resultErrorMessage(ret)}")
            }
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

        io.bind(serverFd, serverAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()).let { ret ->
            if (ret < 0) {
                throw IOException("could not bind $host:$port. ${resultErrorMessage(ret)}")
            }
        }
        io.listen(serverFd, backlog).let { ret ->
            if (ret < 0) {
                throw IOException("could not listen $host:$port. ${resultErrorMessage(ret)}")
            }
        }

        io.serverSocket(serverFd)
    } catch (t: Throwable) {
        io.close(serverFd)
        throw t
    }
}

internal expect fun inet_addr(host: String?): UInt

internal fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}

@OptIn(ExperimentalForeignApi::class)
internal fun resultErrorMessage(result: Int): String {
    val error = if (result < 0) -result else result
    val errStr = strerror(error)?.toKString() ?: "Unknown errno: $error"

    return "$errStr($result)"
}

private fun htons(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}
