package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.IoPoller
import kio.async.SelectionKeyWrapper
import kio.async.SuspendIo
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

actual suspend fun openConnection(
    host: String,
    port: Int,
): AsyncRawConnection {
    val io = currentCoroutineContext().poller.io
    check(io is IoPoller)
    val channel = SocketChannel.open()
    val connectHandle = SelectionKeyWrapper(channel)
    try {
        channel.configureBlocking(false)
        io.attach(connectHandle, SelectionKey.OP_CONNECT)

        channel.connect(InetSocketAddress(host, port))

        while (true) {
            io.connect(channel)

            if (channel.finishConnect()) {
                break
            }
        }

        return ChannelRawAsyncConnection(io, channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    } finally {
        io.detach(connectHandle, SelectionKey.OP_CONNECT)
    }
}

internal class ChannelRawAsyncConnection(
    private val io: SuspendIo,
    private val channel: SocketChannel,
    override val source: AsyncRawSource = asyncChannelRawSource(channel, channel, io),
    override val sink: AsyncRawSink = asyncChannelRawSink(channel, channel, io),
) : AsyncRawConnection {
    private val readHandle = SelectionKeyWrapper(channel)
    private val writeHandle = SelectionKeyWrapper(channel)
    private val ioPoller = io as IoPoller

    init {
        ioPoller.attach(readHandle, SelectionKey.OP_READ)
        ioPoller.attach(writeHandle, SelectionKey.OP_WRITE)
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

            try {
                while (true) {
                    val read = source.readAtMostTo(buf, 1024)
                    if (read == -1L) break
                    buf.skip(buf.size)
                }
            } catch (e: IOException) {
                // Ignore exception when close.
            } finally {
                ioPoller.detach(readHandle, SelectionKey.OP_READ)
                ioPoller.detach(writeHandle, SelectionKey.OP_WRITE)
                channel.close()
            }
        }
    }
}
actual class Socket(val delegate: SocketChannel)
actual class ServerSocket internal constructor(internal val delegate: ServerSocketInternal)

fun SuspendIo.serverSocket(serverChannel: ServerSocketChannel): ServerSocket = ServerSocket(ServerSocketInternal(this, serverChannel))
actual suspend fun Socket.asAsyncConnection(): AsyncRawConnection {
    val io = currentCoroutineContext().poller.io
    return ChannelRawAsyncConnection(io, delegate)
}
actual suspend fun ServerSocket.accept(): Socket = delegate.accept()
actual suspend fun ServerSocket.close() = delegate.close()

internal class ServerSocketInternal(
    private val io: SuspendIo,
    val serverChannel: ServerSocketChannel,
) {

    private val ioPoller = io as IoPoller
    private val acceptHandle = SelectionKeyWrapper(serverChannel)

    init {
        ioPoller.attach(acceptHandle, SelectionKey.OP_ACCEPT)
    }


    suspend fun accept(): Socket {
        while (true) {
            io.accept(serverChannel)

            val client = serverChannel.accept()

            if (client != null) {
                try {
                    client.configureBlocking(false)
                    return Socket(client)
                } catch (t: Throwable) {
                    client.close()
                    throw t
                }
            }
        }
    }

    fun close() {
        ioPoller.detach(acceptHandle, SelectionKey.OP_ACCEPT)
    }
}

actual class SocketAddress internal constructor(
    internal val value: java.net.SocketAddress,
)

actual suspend fun Socket.getLocalAddress(): Result<SocketAddress> = runCatching {
    val address = delegate.localAddress
        ?: throw java.io.IOException("Socket is not bound")
    SocketAddress(address)
}

actual suspend fun Socket.getRemoteAddress(): Result<SocketAddress> = runCatching {
    val address = delegate.remoteAddress
        ?: throw java.io.IOException("Socket is not connected")
    SocketAddress(address)
}

actual suspend fun ServerSocket.getAddress(): Result<SocketAddress> = runCatching {
    val address = delegate.serverChannel.localAddress
        ?: throw java.io.IOException("Socket is not bound")
    SocketAddress(address)
}

actual val SocketAddress.port: Int
    get() = (value as InetSocketAddress).port