package kio.network

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.asyncChannelRawSink
import kio.async.asyncChannelRawSource
import kio.async.awaitAcceptIo
import kio.async.awaitConnectionIo
import kotlinx.io.Buffer
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

actual suspend fun openConnection(
    host: String,
    port: Int,
): AsyncRawConnection {
    val channel = SocketChannel.open()

    try {
        channel.configureBlocking(false)

        channel.connect(InetSocketAddress(host, port))

        while (true) {
            awaitConnectionIo(channel)

            if (channel.finishConnect()) {
                break
            }
        }

        return ChannelRawAsyncConnection(channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    }
}

private class ChannelRawAsyncConnection(
    private val channel: SocketChannel,
    override val source: AsyncRawSource = asyncChannelRawSource(channel, channel),
    override val sink: AsyncRawSink = asyncChannelRawSink(channel, channel),
) : AsyncRawConnection {

    private var closed = false

    override suspend fun close() {
        if (closed) return

        channel.use { channel ->
            try {
                channel.shutdownOutput()
            } catch (_: Throwable) {
            }

            val buf = Buffer()

            while (true) {
                val read = source.readAtMostTo(buf, 1024)
                if (read == -1L) break
                buf.skip(buf.size)
            }
        }
    }
}

actual fun tcpBind(
    host: String,
    port: Int,
): ServerSocket {
    val backlog = 128
    val channel = ServerSocketChannel.open()

    try {
        channel.configureBlocking(false)

        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)

        channel.bind(
            InetSocketAddress(host, port),
            backlog,
        )

        return ChannelServerSocket(channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    }
}

private class ChannelServerSocket(
    private val serverChannel: ServerSocketChannel,
) : ServerSocket {

    override suspend fun accept(): AsyncRawConnection {
        while (true) {
            awaitAcceptIo(serverChannel)

            val client = serverChannel.accept()

            if (client != null) {
                try {
                    client.configureBlocking(false)
                    return ChannelRawAsyncConnection(client)
                } catch (t: Throwable) {
                    client.close()
                    throw t
                }
            }
        }
    }
}
