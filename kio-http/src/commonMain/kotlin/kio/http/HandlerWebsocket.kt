package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.websocket.websocketServerAccept
import kio.websocket.CloseCode
import kio.websocket.ProtocolException
import kio.websocket.WebSocketEvent
import kio.websocket.WsConnection
import kio.websocket.sendTextMessage
import kio.websocket.upgradeToWsConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.Source

fun Route.websocket(uri: String, handler: suspend CoroutineScope.(WebsocketContext) -> Unit) {
    registerCall(HttpMethod.Get, uri) {
        val key = requestHeaders[HttpHeaders.SecWebSocketKey]

        // Do handshake
        responseHead.apply {
            statusCode = HttpStatusCode.SwitchingProtocols
            headers[HttpHeaders.Upgrade] = "websocket"
            headers[HttpHeaders.Connection] = "Upgrade"
            if (key != null) {
                headers[HttpHeaders.SecWebSocketAccept] = websocketServerAccept(key)
            }
        }

        responseSink.flush()

        val wsConnection = conn.upgradeToWsConnection()
        val context = WebsocketContext()

        doWebsocketConnection(wsConnection, context, handler)
    }
}

class WebsocketContext {
    internal val receiveChannel = Channel<WebSocketEvent.Message>()
    val incoming: ReceiveChannel<WebSocketEvent.Message> = receiveChannel

    internal val sendChannel = Channel<Any>()
    internal val outgoing: SendChannel<Any> = sendChannel
}

suspend fun WebsocketContext.sendText(text: String) {
    outgoing.send(text)
}

suspend fun WebsocketContext.sendBinary(buffer: Source) {
    outgoing.send(buffer)
}

private suspend fun doWebsocketConnection(
    wsConnection: WsConnection,
    context: WebsocketContext,
    handler: suspend CoroutineScope.(WebsocketContext) -> Unit
) = coroutineScope {
    val handlerJob = launch {
        coroutineScope { handler(context) }
        wsConnection.sendClose(CloseCode.NORMAL)
    }

    val receiveJob = launch {
        try {
            val buffer = Buffer()
            while (true) {
                buffer.clear()
                when (val event = wsConnection.readMessage(buffer)) {
                    is WebSocketEvent.Close -> {
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
                is Source -> wsConnection.sendBinMessage(message)
                is String -> wsConnection.sendTextMessage(message)
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