package kio.async.io

expect suspend fun openConnection(host: String, port: Int): AsyncRawConnection

interface ServerSocket {
    suspend fun getBoundPort(): Result<Int>

    suspend fun accept(): AsyncRawConnection

    suspend fun close()
}