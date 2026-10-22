package kio.async.io

expect suspend fun openConnection(host: String, port: Int): AsyncRawConnection

expect class SocketAddress
expect val SocketAddress.port: Int

expect class Socket
expect suspend fun Socket.asAsyncConnection(): AsyncRawConnection
expect suspend fun Socket.getLocalAddress(): Result<SocketAddress>
expect suspend fun Socket.getRemoteAddress(): Result<SocketAddress>

expect class ServerSocket
expect suspend fun ServerSocket.getAddress(): Result<SocketAddress>
expect suspend fun ServerSocket.accept(): Socket
expect suspend fun ServerSocket.close()
