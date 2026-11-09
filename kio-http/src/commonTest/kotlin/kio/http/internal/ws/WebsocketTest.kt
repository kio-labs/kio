package kio.http.internal.ws

import kio.async.readByteArray
import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.io.AsyncRawConnection
import kio.async.io.buffered
import kio.async.io.openInMemoryPipe
import kio.websocket.CloseCode
import kio.websocket.ProtocolException
import kio.websocket.Opcode
import kio.websocket.WebSocketEvent
import kio.websocket.WsConnection
import kio.websocket.asWsClientConnection
import kio.websocket.sendFrame
import kio.websocket.sendTextMessage
import kio.websocket.upgradeToWsConnection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.readUShort
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class WebsocketTest {
    @Test fun case1_1_1() = textEchoCase(0)
    @Test fun case1_1_2() = textEchoCase(125)
    @Test fun case1_1_3() = textEchoCase(126)
    @Test fun case1_1_4() = textEchoCase(127)
    @Test fun case1_1_5() = textEchoCase(128)
    @Test fun case1_1_6() = textEchoCase(65535, timeoutSeconds = 10)
    @Test fun case1_1_7() = textEchoCase(65536, timeoutSeconds = 10)

    @Test fun case1_2_1() = binaryEchoCase(0)
    @Test fun case1_2_2() = binaryEchoCase(125)
    @Test fun case1_2_3() = binaryEchoCase(126)
    @Test fun case1_2_4() = binaryEchoCase(127)
    @Test fun case1_2_5() = binaryEchoCase(128)
    @Test fun case1_2_6() = binaryEchoCase(65535, timeoutSeconds = 10)
    @Test fun case1_2_7() = binaryEchoCase(65536, timeoutSeconds = 10)

    @Test fun case2_1() = pingCase(byteArrayOf())
    @Test fun case2_2() = pingCase("Hello, world!".encodeToByteArray())
    @Test fun case2_3() = pingCase(byteArrayOf(0, -1, -2, -3, -4, -5, 0, -1))
    @Test fun case2_4() = pingCase(ByteArray(125) { 0xfe.toByte() })
    @Test fun case2_5() = withWebSocketTest(1) { client ->
        sendFrame(Opcode.PING, Buffer().apply { write(ByteArray(126) { 0xfe.toByte() }) })
        expectClose(CloseCode.PROTOCOL_ERROR)
        client.sendClose(CloseCode.PROTOCOL_ERROR)
    }
    @Test fun case2_7() = unsolicitedPongCase(byteArrayOf())
    @Test fun case2_8() = unsolicitedPongCase("unsolicited pong payload".encodeToByteArray())
    @Test fun case2_9() = withWebSocketTest(1) { client ->
        sendFrame(Opcode.PONG, Buffer().apply { writeString("unsolicited pong payload") })
        val payload = "ping payload".encodeToByteArray()
        sendFrame(Opcode.PING, Buffer().apply { write(payload) })
        expectPong(payload)
        client.sendClose(CloseCode.NORMAL)
        expectClose(CloseCode.NORMAL)
    }
    @Test fun case2_10() = multiplePingsCase()

    @Test fun case5_1() = fragmentedControlCase(Opcode.PING)
    @Test fun case5_2() = fragmentedControlCase(Opcode.PONG)
    @Test fun case5_3() = fragmentedTextCase()
    @Test fun case5_6() = fragmentedTextCase(ping = true)
    @Test fun case5_9() = unexpectedContinuationCase(fin = true)
    @Test fun case5_12() = unexpectedContinuationCase(fin = false)
    @Test fun case5_15() = withWebSocketTest(1) { client ->
        sendFragment(Opcode.TEXT, "fragment1", fin = false)
        sendFragment(Opcode.CONT, "fragment2")
        sendFragment(Opcode.CONT, "fragment3", fin = false)
        sendFragment(Opcode.TEXT, "fragment4")
        assertEquals(WebSocketEvent.Text("fragment1fragment2"), client.readMessage())
        expectClose(CloseCode.PROTOCOL_ERROR)
    }
    @Test fun case5_16() = repeatedUnexpectedContinuationCase(fin = false)
    @Test fun case5_17() = repeatedUnexpectedContinuationCase(fin = true)
    @Test fun case5_18() = withWebSocketTest(1) { _ ->
        sendFragment(Opcode.TEXT, "fragment1", fin = false)
        sendFragment(Opcode.TEXT, "fragment2")
        expectClose(CloseCode.PROTOCOL_ERROR)
    }
    @Test fun case5_19() = interleavedPingsCase()
}

private fun textEchoCase(length: Int, timeoutSeconds: Int = 1) =
    withWebSocketTest(timeoutSeconds) { client ->
        val text = "*".repeat(length)
        sendTextFrame(text)
        assertEquals(WebSocketEvent.Text(text), client.readMessage())
        client.sendClose(CloseCode.NORMAL)
        assertEquals(WebSocketEvent.Close(CloseCode.NORMAL, ""), client.readMessage())
    }

private fun binaryEchoCase(length: Int, timeoutSeconds: Int = 1) =
    withWebSocketTest(timeoutSeconds) { client ->
        val bytes = ByteArray(length) { 0xfe.toByte() }
        sendFrame(Opcode.BIN, Buffer().apply { write(bytes) })
        val message = assertIs<WebSocketEvent.Binary>(client.readMessage())
        assertContentEquals(bytes, message.buffer.readByteArray())
        client.sendClose(CloseCode.NORMAL)
        assertEquals(WebSocketEvent.Close(CloseCode.NORMAL, ""), client.readMessage())
    }

private fun pingCase(payload: ByteArray, timeoutSeconds: Int = 1) =
    withWebSocketTest(timeoutSeconds) { client ->
        sendFrame(Opcode.PING, Buffer().apply { write(payload) })
        expectPong(payload)
        client.sendClose(CloseCode.NORMAL)
        expectClose(CloseCode.NORMAL)
    }

private fun unsolicitedPongCase(payload: ByteArray) = withWebSocketTest(1) { client ->
    sendFrame(Opcode.PONG, Buffer().apply { write(payload) })
    client.sendClose(CloseCode.NORMAL)
    // The next frame must be Close: no reply to unsolicited Pong.
    expectClose(CloseCode.NORMAL)
}

private fun multiplePingsCase() = withWebSocketTest(3) { client ->
    val payloads = List(10) { "payload-$it".encodeToByteArray() }
    payloads.forEach { sendFrame(Opcode.PING, Buffer().apply { write(it) }) }
    payloads.forEach { expectPong(it) }
    client.sendClose(CloseCode.NORMAL)
    expectClose(CloseCode.NORMAL)
}

// Autobahn Case5: WebSocket fragmentation, independent of transport chopping.
private fun fragmentedControlCase(opcode: Opcode) = withWebSocketTest(1) { _ ->
    sendFragment(opcode, "fragment1", fin = false)
    sendFragment(Opcode.CONT, "fragment2")
    expectClose(CloseCode.PROTOCOL_ERROR)
}

private fun fragmentedTextCase(ping: Boolean = false) =
    withWebSocketTest(1) { client ->
        sendFragment(Opcode.TEXT, "fragment1", fin = false)
        if (ping) sendFragment(Opcode.PING, "ping payload")
        sendFragment(Opcode.CONT, "fragment2")
        if (ping) expectPong("ping payload".encodeToByteArray())
        assertEquals(WebSocketEvent.Text("fragment1fragment2"), client.readMessage())
        client.sendClose(CloseCode.NORMAL)
        expectClose(CloseCode.NORMAL)
    }

private fun unexpectedContinuationCase(fin: Boolean) =
    withWebSocketTest(1) { _ ->
        sendFragment(Opcode.CONT, "non-continuation payload", fin = fin)
        sendFragment(Opcode.TEXT, "Hello, world!")
        expectClose(CloseCode.PROTOCOL_ERROR)
    }

private fun repeatedUnexpectedContinuationCase(fin: Boolean) = withWebSocketTest(1) { _ ->
    repeat(2) {
        sendFragment(Opcode.CONT, "fragment1", fin = fin)
        sendFragment(Opcode.TEXT, "fragment2", fin = false)
        sendFragment(Opcode.CONT, "fragment3")
    }
    expectClose(CloseCode.PROTOCOL_ERROR)
}

private fun interleavedPingsCase() = withWebSocketTest(3) { client ->
    sendFragment(Opcode.TEXT, "fragment1", fin = false)
    sendFragment(Opcode.CONT, "fragment2", fin = false)
    sendFragment(Opcode.PING, "pongme 1!")
    delay(1_000.milliseconds) // Virtual time under runTest, as in upstream continueLater(1).
    // Must receive Pong before sending the rest of the fragmented message.
    expectPong("pongme 1!".encodeToByteArray())
    sendFragment(Opcode.CONT, "fragment3", fin = false)
    sendFragment(Opcode.CONT, "fragment4", fin = false)
    sendFragment(Opcode.PING, "pongme 2!")
    sendFragment(Opcode.CONT, "fragment5")
    expectPong("pongme 2!".encodeToByteArray())
    assertEquals(WebSocketEvent.Text("fragment1fragment2fragment3fragment4fragment5"), client.readMessage())
    client.sendClose(CloseCode.NORMAL)
    expectClose(CloseCode.NORMAL)
}

private fun withWebSocketTest(
    timeoutSeconds: Int,
    block: suspend WebsocketTestScope.(WsConnection) -> Unit,
) = runTest(timeout = timeoutSeconds.seconds) {
    val mock = MockWebsocketServerConnection()
    val scope = WebsocketTestScope(this.coroutineContext, mock)

    val serverJob = launch {
        try {
            val buffer = Buffer()
            while (isActive) {
                buffer.skip(buffer.size)
                when (val msg = mock.wsServerConn.readMessage(buffer)) {
                    is WebSocketEvent.Text -> mock.wsServerConn.sendTextMessage(msg.text)
                    is WebSocketEvent.Binary -> mock.wsServerConn.sendBinMessage(msg.buffer)
                    is WebSocketEvent.Close -> break
                }
            }
        } catch (e: ProtocolException) {
            // Same exception-to-Close handling as HandlerWebsocket.
            mock.wsServerConn.sendClose(e.closeCode, e.message)
        }
    }

    try {
        scope.block(mock.wsClientConn)
        serverJob.join()
    } finally {
        serverJob.cancelAndJoin()
        mock.clientConnection.close()
        mock.serverConnection.close()
    }
}

private class WebsocketTestScope(
    override val coroutineContext: CoroutineContext,
    private val mockConn: MockWebsocketServerConnection,
) : CoroutineScope {
    private suspend fun readControlFrame(opcode: Opcode): ByteArray {
        val source = mockConn.clientConnection.source
        assertEquals(0x80 or opcode.code, source.readByte().toInt() and 0xff)
        val length = source.readByte().toInt() and 0xff
        // Server control frames are unmasked and have at most 125 payload bytes.
        kotlin.test.assertTrue(length <= 125)
        return source.readByteArray(length)
    }

    suspend fun expectPong(payload: ByteArray) {
        assertContentEquals(payload, readControlFrame(Opcode.PONG))
    }

    suspend fun expectClose(code: CloseCode) {
        val payload = Buffer().apply { write(readControlFrame(Opcode.CLOSE)) }
        assertEquals(code.code, payload.readUShort().toInt())
    }

    suspend fun sendTextFrame(text: String) {
        val payload = Buffer().apply { writeString(text) }
        sendFrame(Opcode.TEXT, payload)
    }

    suspend fun sendFragment(opcode: Opcode, text: String, fin: Boolean = true) {
        sendFrame(opcode, Buffer().apply { writeString(text) }, fin)
    }

    suspend fun sendFrame(opcode: Opcode, payload: Buffer, fin: Boolean = true) {
        mockConn.clientConnection.sink.sendFrame(
            isClient = true,
            isFin = fin,
            opcode = opcode,
            payload = payload,
            payloadLength = payload.size.toULong(),
        )
    }
}

private class MockWebsocketServerConnection() {
    private val clientPipeConn = openInMemoryPipe()
    private val serverPipeConn = openInMemoryPipe()

    val serverConnection = object : AsyncRawConnection {
        override val source: AsyncRawSource = clientPipeConn.source

        override val sink: AsyncRawSink = serverPipeConn.sink

        override suspend fun close() {
            source.close()
            sink.close()
        }
    }.buffered()

    val clientConnection = object : AsyncRawConnection {
        override val source: AsyncRawSource = serverPipeConn.source

        override val sink: AsyncRawSink = clientPipeConn.sink

        override suspend fun close() {
            source.close()
            sink.close()
        }
    }.buffered()

    val wsServerConn = serverConnection.upgradeToWsConnection()
    val wsClientConn = clientConnection.asWsClientConnection()
}