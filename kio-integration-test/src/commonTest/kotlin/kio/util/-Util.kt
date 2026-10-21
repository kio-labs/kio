package kio.util

import kio.async.io.ServerSocket

expect suspend fun tcpBind(
    host: String,
    port: Int,
): ServerSocket