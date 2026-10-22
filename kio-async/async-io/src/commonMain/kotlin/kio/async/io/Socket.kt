package kio.async.io

expect suspend fun openConnection(host: String, port: Int): AsyncRawConnection
expect suspend fun Socket.asAsyncConnection(): AsyncRawConnection

expect class ServerSocket
expect class Socket

expect suspend fun ServerSocket.getBoundPort(): Result<Int>
expect suspend fun ServerSocket.accept(): Socket
expect suspend fun ServerSocket.close()
