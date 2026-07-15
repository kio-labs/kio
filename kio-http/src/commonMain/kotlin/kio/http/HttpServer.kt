package kio.http

import io.ktor.http.HttpMethod
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
    registerBlock: suspend RouteScope.() -> Unit
) {
    val scope = RouteScope()
    scope.registerBlock()

    startHttpServer(
        scope,
        serverSocket,
        connectionWrapper,
    )
}

class RouteScope {
    data class RouteKey(val method: HttpMethod, val uri: String)

    internal val httpCallInterceptors: ArrayDeque<CallInterceptor> = ArrayDeque()

    private val httpCallHandlers = mutableMapOf<RouteKey, CallHandler>()

    internal fun registerCallHandler(key: RouteKey, handler: CallHandler) {
        if (httpCallHandlers.contains(key)) error("route ($key) already registered.")
        httpCallHandlers[key] = handler
    }

    internal fun getCallHandler(key: RouteKey): CallHandler? {
        return httpCallHandlers[key]
    }

    internal val websocketHandlers = mutableMapOf<String, WebsocketHandler>()

    internal fun registerWebsocketHandler(uri: String, handler: WebsocketHandler) {
        val key = RouteKey(HttpMethod.Get, uri)
        if (httpCallHandlers.contains(key)) error("route ($key) already registered.")

        websocketHandlers[uri] = handler
    }

    internal fun getWebsocketHandler(uri: String): WebsocketHandler? {
        return websocketHandlers[uri]
    }
}

private suspend fun CoroutineScope.startHttpServer(
    routeScope: RouteScope,
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
                    "h2" -> routeScope.http2Connection(conn)
                    else -> routeScope.http1Connection(conn)
                }
            } catch (e: IOException) {
                println("Connection processing failed: $e")
            } finally {
                conn.close()
            }
        }
    }
}
