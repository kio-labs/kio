package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_ACCEPT
import kio.async.POLL_INTEREST_CONNECT
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.Poller
import kio.async.SelectionKeyWrapper
import kio.async.awaitIo
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

actual suspend fun openConnection(
    host: String,
    port: Int,
): AsyncRawConnection {
    val poller = currentCoroutineContext().poller
    val channel = SocketChannel.open()
    val connectHandle = SelectionKeyWrapper(channel)
    try {
        channel.configureBlocking(false)
        poller.attach(connectHandle, POLL_INTEREST_CONNECT)

        channel.connect(InetSocketAddress(host, port))

        while (true) {
            awaitIo(SelectionKeyWrapper(channel), POLL_INTEREST_CONNECT)

            if (channel.finishConnect()) {
                break
            }
        }

        return ChannelRawAsyncConnection(poller, channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    } finally {
        poller.detach(connectHandle, POLL_INTEREST_CONNECT)
    }
}

internal class ChannelRawAsyncConnection(
    private val poller: Poller,
    private val channel: SocketChannel,
    override val source: AsyncRawSource = asyncChannelRawSource(channel, channel),
    override val sink: AsyncRawSink = asyncChannelRawSink(channel, channel),
) : AsyncRawConnection {
    private val readHandle = SelectionKeyWrapper(channel)
    private val writeHandle = SelectionKeyWrapper(channel)

    init {
        poller.attach(readHandle, POLL_INTEREST_READ)
        poller.attach(writeHandle, POLL_INTEREST_WRITE)
    }

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

        poller.detach(readHandle, POLL_INTEREST_READ)
        poller.detach(writeHandle, POLL_INTEREST_WRITE)
    }
}

actual suspend fun tcpBind(
    host: String,
    port: Int,
): ServerSocket {
    val poller = currentCoroutineContext().poller
    val backlog = 128
    val channel = ServerSocketChannel.open()

    try {
        channel.configureBlocking(false)

        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)

        channel.bind(
            InetSocketAddress(host, port),
            backlog,
        )

        return ChannelServerSocket(poller, channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    }
}

private class ChannelServerSocket(
    private val poller: Poller,
    private val serverChannel: ServerSocketChannel,
) : ServerSocket {

    private val acceptHandle = SelectionKeyWrapper(serverChannel)

    init {
        poller.attach(acceptHandle, POLL_INTEREST_ACCEPT)
    }

    override suspend fun accept(): AsyncRawConnection {
        while (true) {
            awaitIo(SelectionKeyWrapper(serverChannel), POLL_INTEREST_ACCEPT)

            val client = serverChannel.accept()

            if (client != null) {
                try {
                    client.configureBlocking(false)
                    return ChannelRawAsyncConnection(poller, client)
                } catch (t: Throwable) {
                    client.close()
                    throw t
                }
            }
        }
    }

    override fun close() {
        poller.detach(acceptHandle, POLL_INTEREST_ACCEPT)
    }
}
