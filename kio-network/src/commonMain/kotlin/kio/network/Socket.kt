package kio.network

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered

interface AsyncRawConnection {
    val source: AsyncRawSource
    val sink: AsyncRawSink
    suspend fun close()
}

fun AsyncRawConnection.buffered() = object : AsyncConnection {
    val delegate = this@buffered
    override val source: AsyncSource = delegate.source.buffered()
    override val sink: AsyncSink = delegate.sink.buffered()

    override suspend fun close() = delegate.close()

}
interface AsyncConnection: AsyncRawConnection {
    override val source: AsyncSource
    override val sink: AsyncSink
    override suspend fun close()
}

expect suspend fun openConnection(host: String, port: Int): AsyncRawConnection

expect fun tcpBind(host: String, port: Int): ServerSocket

interface ServerSocket {
    suspend fun accept(): AsyncRawConnection
}