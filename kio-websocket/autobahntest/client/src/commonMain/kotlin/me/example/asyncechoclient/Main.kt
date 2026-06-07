package me.example.asyncechoclient

import kio.async.runPollEventLoop
import kio.network.openConnection
import kio.websocket.KioWsConnection
import kio.websocket.MessageType
import kio.websocket.ProtocolException
import kio.websocket.WebSocketEvent
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlinx.io.readString
import kio.async.poller.poll.PosixPoll

private const val HOST = "127.0.0.1"
private const val PORT = 9001
private const val AGENT = "kio"

fun main(): Unit = runPollEventLoop(PosixPoll) {
    val count = getCaseCount()

    for (caseId in 1..count) {
        runCase(caseId.toString())
    }

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


private suspend fun getCaseCount(): Int {
    val conn = openConnection(HOST, PORT)
    val ws = KioWsConnection(conn, isClient = true)

    try {
        ws.clientHandShake(
            path = "/getCaseCount",
            host = "$HOST:$PORT",
        ).getOrThrow()

        val buffer = Buffer()

        while (true) {
            when (val event = ws.readMessage(buffer).getOrThrow()) {
                WebSocketEvent.Close -> break

                is WebSocketEvent.Message -> {
                    val text = buffer.readString()
                    return text.trim().toInt()
                }
            }
        }

        error("failed to get case count")
    } finally {
        runCatching { ws.close() }
    }
}
