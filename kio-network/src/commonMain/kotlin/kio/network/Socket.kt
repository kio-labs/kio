package kio.network

import kio.async.AsyncSink
import kio.async.AsyncSource

interface AsyncConnection {
    val source: AsyncSource
    val sink: AsyncSink
    suspend fun close()
}

expect suspend fun openConnection(host: String, port: Int): AsyncConnection