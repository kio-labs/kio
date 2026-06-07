package me.example.asyncechoserver

import kio.async.runPollEventLoop
import kio.network.AsyncConnection
import kio.network.tcpBind
import kio.websocket.*
import kotlinx.cinterop.*
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.IOException
import platform.posix.*
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kio.async.poller.poll.PosixPoll

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop(PosixPoll) {
    signal(SIGPIPE, SIG_IGN)

    val server = tcpBind(HOST_IP, PORT)

    while (true) {
        val conn = server.accept()
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
        webSocketClient.serverHandShake().getOrThrow()

        while (true) {
            val buffer = Buffer()
            when (val event = webSocketClient.readMessage(buffer).getOrThrow()) {
                WebSocketEvent.Close -> {
                    println("INFO: client close event recieved(${conn})")
                    break
                }

                is WebSocketEvent.Message -> when (event.type) {
                    MessageType.TEXT -> webSocketClient.sendTextMessage(buffer)
                    MessageType.BIN -> webSocketClient.sendBinMessage(buffer)
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