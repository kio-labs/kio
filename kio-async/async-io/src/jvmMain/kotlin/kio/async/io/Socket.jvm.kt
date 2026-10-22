package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_ACCEPT
import kio.async.POLL_INTEREST_CONNECT
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SelectionKeyWrapper
import kio.async.SuspendIo
import kio.async.attachKey
import kio.async.detachKey
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
    val io = currentCoroutineContext().poller.io
    val channel = SocketChannel.open()
    val connectHandle = SelectionKeyWrapper(channel)
    try {
        channel.configureBlocking(false)
        io.attachKey(connectHandle, POLL_INTEREST_CONNECT)

        channel.connect(InetSocketAddress(host, port))

        while (true) {
            io.suspendConnect(channel)

            if (channel.finishConnect()) {
                break
            }
        }

        return ChannelRawAsyncConnection(io, channel)
    } catch (t: Throwable) {
        channel.close()
        throw t
    } finally {
        io.detachKey(connectHandle, POLL_INTEREST_CONNECT)
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

    init {
        io.attachKey(readHandle, POLL_INTEREST_READ)
        io.attachKey(writeHandle, POLL_INTEREST_WRITE)
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

        io.detachKey(readHandle, POLL_INTEREST_READ)
        io.detachKey(writeHandle, POLL_INTEREST_WRITE)
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

    private val acceptHandle = SelectionKeyWrapper(serverChannel)

    init {
        io.attachKey(acceptHandle, POLL_INTEREST_ACCEPT)
    }


    suspend fun accept(): Socket {
        while (true) {
            io.suspendAccept(serverChannel)

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
        io.detachKey(acceptHandle, POLL_INTEREST_ACCEPT)
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