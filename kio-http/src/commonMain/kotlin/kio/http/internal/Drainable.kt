package kio.http.internal

internal interface Drainable {
    suspend fun drain()
}