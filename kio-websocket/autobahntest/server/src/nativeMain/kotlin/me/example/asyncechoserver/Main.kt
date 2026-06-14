package me.example.asyncechoserver

import kio.async.AsyncSource
import kio.async.runPollEventLoop
import kio.network.AsyncConnection
import kio.network.tcpBind
import kio.websocket.*
import kotlinx.cinterop.*
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import platform.posix.*
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kio.async.poller.poll.PosixPoll
import kio.async.readLine
import kio.async.writeString
import kio.network.buffered
import org.kotlincrypto.hash.sha1.SHA1
import kotlin.io.encoding.Base64

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop(PosixPoll) {
    signal(SIGPIPE, SIG_IGN)

    val server = tcpBind(HOST_IP, PORT)

    while (true) {
        val conn = server.accept().buffered()
        launch {
            handleWebSocketConnection(conn)
        }
    }
}

@OptIn(ObsoleteWorkersApi::class)
private suspend fun handleWebSocketConnection(conn: AsyncConnection) {
    println("INFO: webSocket Start.. ${Worker.current.name}")
    val webSocketClient = KioWsConnection(conn, false)
    try {
        conn.doServerHandShake()

        while (true) {
            when (val event = webSocketClient.readMessage()) {
                WebSocketEvent.Close -> {
                    println("INFO: client close event recieved(${conn})")
                    break
                }

                is WebSocketEvent.Message -> when (event) {
                    is WebSocketEvent.Text -> webSocketClient.sendTextMessage(text = event.text)
                    is WebSocketEvent.Binary -> webSocketClient.sendBinMessage(event.buffer)
                }
            }
        }
    } catch (t: ProtocolException) {
        webSocketClient.sendClose(t.closeCode, t.message)
    } catch (t: IOException) {
        println("ioException: $t, ${t.printStackTrace()}")
        throw t
    } finally {
        println("client closed $conn")
        webSocketClient.close()
    }
}

internal suspend fun AsyncConnection.doServerHandShake() {
    val conn = this
    val headers = conn.source.parseHeaders()
    val secWebsocketKey = headers["Sec-WebSocket-Key"]
        ?: throw IOException("Sec-WebSocket-Key is not set by client")

    val handShake = buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: ${calculateServerAcceptKey(secWebsocketKey)}\r\n")
        append("\r\n")
    }

    conn.sink.writeString(handShake, 0, handShake.length)
    conn.sink.flush()
}

internal const val SEC_MAGIC_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

internal fun calculateServerAcceptKey(key: String) =
    Base64.encode(sha1((key + SEC_MAGIC_KEY).encodeToByteArray()))

internal fun sha1(bytes: ByteArray): ByteArray {
    return SHA1().digest(bytes)
}

// TODO: Support Http response info parsing
internal suspend fun AsyncSource.parseHeaders(): Map<String, String> {
    return buildMap {
        while (!exhausted()) {
            val line = readLine()
            println("http res :: $line")
            if (line.isNullOrEmpty()) break
            if (!line.contains(':')) {
                // not header, continue
                continue
            }

            val (key, value) = line.split(':')
            val headerValue = value.trim()
            put(key, headerValue)
        }
    }
}
