package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseHeaderValue
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
                routeScope.handleConnection(conn)
            } catch (e: IOException) {
                e.printStackTrace()
                println("exception when try to keep connection alive $e")
            } finally {
                conn.close()
            }
        }
    }
}

private suspend fun RouteScope.handleConnection(conn: AsyncConnection) {
    while (true) {
        val requestHead = conn.source.parseRequestHead()

        when {
            requestHead.isWebSocketUpgrade() -> {
                handleWebsocketRequest(requestHead, conn)
                // websocket closed, break the loop to close this connection.
                break
            }

            // Fall back to normal http request handle
            else -> handleHttpRequest(requestHead, conn)
        }
    }
}

private fun HttpRequestHead.isWebSocketUpgrade(): Boolean {
    val connection = parseHeaderValue(headers[HttpHeaders.Connection])
    val upgrade = headers[HttpHeaders.Upgrade]

    return method == HttpMethod.Get &&
            connection.map { it.value.lowercase() }.contains("upgrade") &&
            upgrade.equals("websocket", ignoreCase = true) &&
            headers[HttpHeaders.SecWebSocketKey] != null &&
            headers[HttpHeaders.SecWebSocketVersion] == "13"
}
