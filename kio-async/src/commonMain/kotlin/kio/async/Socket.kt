package kio.async

interface AsyncConnection {
    val source: AsyncSource
    val sink: AsyncSink
    suspend fun close()
}

expect suspend fun openConnection(host: String, port: Int): AsyncConnection