package kio.websocket

import kio.async.PollerFactory
import kio.async.io.accept
import kio.async.io.asAsyncConnection
import kio.async.io.buffered
import kio.async.io.openConnection
import kio.async.runPollEventLoop
import kio.util.getBoundPort
import kio.util.tcpBind
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

abstract class WebsocketConnectionTest {
    abstract val pollerFactory: PollerFactory

    @Test
    fun smokeTest() = runPollEventLoop(pollerFactory) {
        val server = tcpBind("127.0.0.1", 0)
        val serverPort = server.getBoundPort().getOrThrow()

        val clientJob = launch {
            val conn = openConnection("127.0.0.1", serverPort).buffered().asWsClientConnection()
            conn.sendTextMessage("Hello")
        }

        val webSocketConn = server.accept().asAsyncConnection().buffered().upgradeToWsConnection()
        val message = webSocketConn.readMessage()
        assertIs<WebSocketEvent.Text>(message)
        assertEquals("Hello", message.text)
    }
}