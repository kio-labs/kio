package kio.async.io

expect suspend fun openConnection(host: String, port: Int): AsyncRawConnection

expect suspend fun tcpBind(host: String, port: Int): ServerSocket

interface ServerSocket {
    val boundPort: Int

    suspend fun accept(): AsyncRawConnection

    fun close()
}