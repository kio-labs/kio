package kio.util

import kio.async.io.ServerSocket
import kio.async.io.getAddress
import kio.async.io.port

expect suspend fun tcpBind(
    host: String,
    port: Int,
): ServerSocket

suspend fun ServerSocket.getBoundPort(): Result<Int> = getAddress().map { it.port }
