package kio.http

import kio.async.io.AsyncConnection
import kio.async.io.AsyncRawConnection
import kio.async.io.ServerSocket
import kio.async.io.buffered
import kio.http.internal.http1.http1Connection
import kio.http.internal.http2.http2Connection
import kio.tls.SslConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException

suspend fun CoroutineScope.httpServer(
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection = { buffered() },
    block: suspend Route.() -> Unit
) {
    val route = Route(RootSegment, ArrayDeque())
    route.block()

    startHttpServer(
        route,
        serverSocket,
        connectionWrapper,
    )
}

private suspend fun CoroutineScope.startHttpServer(
    route: Route,
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection,
) {
    while (true) {
        val raw = try {
            serverSocket.accept()
        } catch (t: IOException) {
            println("failed when accept new connection $t")
            continue
        }

        val conn = raw.connectionWrapper()
        launch {
            try {
                val sslConnection = conn as? SslConnection
                sslConnection?.handShake()
                val selectedAlpn = sslConnection?.getSelectedAlpn()
                when (selectedAlpn) {
                    "h2" -> route.http2Connection(conn)
                    else -> route.http1Connection(conn)
                }
            } catch (e: IOException) {
                println("Connection processing failed: $e")
            } finally {
                conn.close()
            }
        }
    }
}
