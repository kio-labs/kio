package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.websocket.websocketServerAccept
import kio.async.buffered
import kio.http.internal.HttpRequestHead
import kio.http.internal.HttpResponseHead
import kio.http.internal.http1.http1ResponseSink
import kio.network.AsyncConnection
import kio.websocket.CloseCode
import kio.websocket.ProtocolException
import kio.websocket.WebSocketEvent
import kio.websocket.WsConnection
import kio.websocket.sendTextMessage
import kio.websocket.upgradeToWsConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer

fun RouteScope.websocket(uri: String, handler: suspend (WebsocketContext) -> Unit) {
    registerWebsocketHandler(uri, handler)
}

typealias WebsocketHandler = suspend WebsocketContext.() -> Unit

class WebsocketContext {
    internal val receiveChannel = Channel<WebSocketEvent.Message>()
    val incoming: ReceiveChannel<WebSocketEvent.Message> = receiveChannel

    internal val sendChannel = Channel<WebSocketEvent.Message>()
    val outgoing: SendChannel<WebSocketEvent.Message> = sendChannel
}

suspend fun WebsocketContext.sendText(text: String) {
    outgoing.send(WebSocketEvent.Text(text))
}

suspend fun WebsocketContext.sendBinary(buffer: Buffer) {
    outgoing.send(WebSocketEvent.Binary(buffer))
}

internal suspend fun RouteScope.handleWebsocketRequest(
    head: HttpRequestHead,
    conn: AsyncConnection
) {
    val responseHead = HttpResponseHead.Builder()
    val sink = conn.sink.http1ResponseSink(responseHead).buffered()

    val handler = getWebsocketHandler(head.uri)
    if (handler == null) {
// TODO: Is it correct to send Status code 404?
        responseHead.statusCode = HttpStatusCode.NotFound
        sink.flush()
        return
    }

    val key = head.headers[HttpHeaders.SecWebSocketKey]
    if (key == null) {
        responseHead.statusCode = HttpStatusCode.BadRequest
        sink.flush()
        return
    }

    // Do handshake
    responseHead.apply {
        statusCode = HttpStatusCode.SwitchingProtocols
        headers[HttpHeaders.Upgrade] = "websocket"
        headers[HttpHeaders.Connection] = "Upgrade"
        headers[HttpHeaders.SecWebSocketAccept] = websocketServerAccept(key)
    }

    sink.flush()

    val wsConnection = conn.upgradeToWsConnection()
    val context = WebsocketContext()

    doWebsocketConnection(wsConnection, context, handler)
}

private suspend fun doWebsocketConnection(
    wsConnection: WsConnection,
    context: WebsocketContext,
    handler: WebsocketHandler
) = coroutineScope {
    val handlerJob = launch {
        handler(context)
        wsConnection.sendClose(CloseCode.NORMAL)
    }

    val receiveJob = launch {
        try {
            while (true) {
                when (val event = wsConnection.readMessage()) {
                    WebSocketEvent.Close -> {
                        break
                    }

                    is WebSocketEvent.Message -> {
                        context.receiveChannel.send(event)
                    }
                }
            }
        } catch (t: ProtocolException) {
            wsConnection.sendClose(t.closeCode, t.message)
        }
    }

    val sendJob = launch {
        for (message in context.sendChannel) {
            when (message) {
                is WebSocketEvent.Binary -> wsConnection.sendBinMessage(message.buffer)
                is WebSocketEvent.Text -> wsConnection.sendTextMessage(message.text)
            }
        }
    }

    receiveJob.invokeOnCompletion {
        handlerJob.cancel()
        sendJob.cancel()
    }

    handlerJob.invokeOnCompletion {
        receiveJob.cancel()
        sendJob.cancel()
    }
}