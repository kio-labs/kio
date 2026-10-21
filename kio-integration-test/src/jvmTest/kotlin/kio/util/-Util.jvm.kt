package kio.util

import kio.async.io.ServerSocket
import kio.async.io.serverSocket
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel

actual suspend fun tcpBind(
    host: String,
    port: Int,
): ServerSocket {
    val io = currentCoroutineContext().poller.io
    val backlog = 128
    val channel = ServerSocketChannel.open()

    try {
        channel.configureBlocking(false)

        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)

        channel.bind(
            InetSocketAddress(host, port),
            backlog,
        )

        return io.serverSocket(channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    }
}
