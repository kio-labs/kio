package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.websocket.websocketServerAccept
import kio.websocket.CloseCode
import kio.websocket.ProtocolException
import kio.websocket.WebSocketEvent
import kio.websocket.WsConnection
import kio.websocket.upgradeToWsConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString

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

suspend fun WebsocketContext.sendBinary(buffer: Source, length: Long) {
    outgoing.send(WebSocketBin(buffer, length))
}

private data class WebSocketBin(val data: Source, val length: Long)

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
        val bufferToSend = Buffer()
        for (message in context.sendChannel) {
            check(bufferToSend.size == 0L)
            when (message) {
                is WebSocketBin -> {
                    val (buffer, length) = message
                    bufferToSend.write(buffer, length)
                    wsConnection.sendBinMessage(bufferToSend)
                }
                is String -> {
                    bufferToSend.writeString(message)
                    wsConnection.sendTextMessage(bufferToSend)
                }
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