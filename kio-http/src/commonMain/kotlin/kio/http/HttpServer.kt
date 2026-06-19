package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseHeaderValue
import kio.async.buffered
import kio.async.readString
import kio.http.internal.HttpRequestHead
import kio.http.internal.http1.parseRequestHead
import kio.http.internal.http2.ContinuationSource
import kio.http.internal.http2.Frame
import kio.http.internal.http2.Hpack
import kio.http.internal.http2.Http2Stream
import kio.http.internal.http2.nextFrame
import kio.http.internal.http2.readPreface
import kio.http.internal.http2.toHttpRequestHead
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

private suspend fun RouteScope.handleHttp2Connection(conn: AsyncConnection) {
    conn.source.readPreface()

    val continuation = ContinuationSource(conn.source)
    val hpackReader: Hpack.Reader =
        Hpack.Reader(
            source = continuation,
            headerTableSizeSetting = 4096,
        )
    val streams = mutableMapOf<Int, Http2Stream>()
    context(hpackReader, continuation) {
        while (true) {
            when (val frame = conn.source.nextFrame()) {
                is Frame.Headers -> {
                    streams[frame.streamId] = Http2Stream(
                        streamId = frame.streamId,
                        requestHeader = frame.headerBlock.toHttpRequestHead()
                    )
                }
                is Frame.Data -> {
                    val dataStream = streams[frame.streamId]
                    if (dataStream == null ) {
                        conn.source.skip(frame.length)
                        return
                    }
                    dataStream.receiveData(conn.source, frame.length, frame.inFinished)
                }

                Frame.AckSettings -> {}
                is Frame.Setting -> {}
                is Frame.WindowUpdate -> {}
            }
        }
    }
}

private suspend fun RouteScope.handleHttp1Connection(conn: AsyncConnection) {
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
