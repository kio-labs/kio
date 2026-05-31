package me.example.asyncechoclient

import kio.async.openConnection
import kio.async.runPollEventLoop
import kio.websocket.KioWsConnection
import kio.websocket.MessageType
import kio.websocket.ProtocolException
import kio.websocket.WebSocketEvent
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException

private const val HOST = "127.0.0.1"
private const val PORT = 9001
private const val AGENT = "kio"

fun main(): Unit = runPollEventLoop {
    runCase("1")
//    runCase("1.1.2")
//    runCase("1.1.3")
//    runCase("1.1.4")
//    runCase("1.1.5")
//    runCase("1.1.6")
//    runCase("1.1.7")
//    runCase("1.1.8")
//    runCase("1.2.1")
//    runCase("1.2.2")
//    runCase("1.2.3")
//    runCase("1.2.4")
//    runCase("1.2.5")
//    runCase("1.2.6")
//    runCase("1.2.7")
//    runCase("1.2.8")
    updateReports()
}

private suspend fun runCase(caseId: String) {
    val conn = openConnection(HOST, PORT)
    val ws = KioWsConnection(conn, isClient = true)

    println("running case $caseId")

    try {
        ws.clientHandShake(
            path = "/runCase?case=$caseId&agent=$AGENT",
            host = "$HOST:$PORT",
        ).getOrThrow()

        println("handshake complete")

        while (true) {
            val buffer = Buffer()

            val event = ws.readMessage(buffer).getOrThrow()

            println("event received $event")

            when (event) {
                WebSocketEvent.Close -> {
                    println("close received")
                    break
                }

                is WebSocketEvent.Message -> {
                    when (event.type) {
                        MessageType.TEXT -> {
                            println("echo text")
                            ws.sendTextMessage(buffer)
                        }

                        MessageType.BIN -> {
                            println("echo binary")
                            ws.sendBinMessage(buffer)
                        }
                    }
                }
            }
        }
    } catch (t: ProtocolException) {
        println("protocol error: ${t.message}")
        ws.sendClose(t.closeCode, t.message)
    } catch (t: IOException) {
        println("io error: $t")
        throw t
    } finally {
        println("case $caseId finished")
        ws.close()
    }
}

private suspend fun updateReports() {
    val conn = openConnection(HOST, PORT)
    val ws = KioWsConnection(conn, isClient = true)

    println("updating reports")

    try {
        ws.clientHandShake(
            path = "/updateReports?agent=$AGENT",
            host = "$HOST:$PORT",
        ).getOrThrow()

        while (true) {
            val buffer = Buffer()

            val event = try {
                ws.readMessage(buffer).getOrThrow()
            } catch (e: EOFException) {
                break
            }

            if (event == WebSocketEvent.Close) break
        }
    } finally {
        ws.close()
    }

    println("reports updated")
}