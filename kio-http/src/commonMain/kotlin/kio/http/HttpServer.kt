package kio.http

import io.ktor.http.HttpMethod
import kio.http.internal.http2.handleHttp2Connection
import kio.network.AsyncConnection
import kio.network.AsyncRawConnection
import kio.network.ServerSocket
import kio.network.buffered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.experimental.ExperimentalNativeApi

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

@OptIn(ExperimentalNativeApi::class)
private suspend fun CoroutineScope.startHttpServer(
    routeScope: RouteScope,
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection,
) {
    while (true) {
        val raw = serverSocket.accept()
        val conn = raw.connectionWrapper()

        launch {
            try {
                routeScope.handleHttp2Connection(conn)
            } catch (e: IOException) {
                println("exception when try to keep connection alive $e")
            } finally {
                conn.close()
            }
        }
    }
}
