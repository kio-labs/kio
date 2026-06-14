package me.example.asyncechoclient

import kio.async.AsyncSource
import kio.async.runPollEventLoop
import kio.network.openConnection
import kio.websocket.ProtocolException
import kio.websocket.WebSocketEvent
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kio.async.poller.poll.PosixPoll
import kio.async.readLine
import kio.async.writeString
import kio.network.AsyncConnection
import kio.websocket.asWsClientConnection
import kio.websocket.sendTextMessage
import org.kotlincrypto.hash.sha1.SHA1
import kotlin.io.encoding.Base64

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
    val ws = conn.asWsClientConnection()

    println("running case $caseId")

    try {
        conn.doClientHandShake(
            path = "/runCase?case=$caseId&agent=$AGENT",
            host = "$HOST:$PORT",
        )

        println("handshake complete")

        while (true) {
            val event = ws.readMessage()

            println("event received $event")

            when (event) {
                WebSocketEvent.Close -> {
                    println("close received")
                    break
                }

                is WebSocketEvent.Message -> {
                    when (event) {
                        is WebSocketEvent.Text -> {
                            println("echo text")
                            ws.sendTextMessage(event.text)
                        }

                        is WebSocketEvent.Binary -> {
                            println("echo binary")
                            ws.sendBinMessage(event.buffer)
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
    val ws = conn.asWsClientConnection()

    println("updating reports")

    try {
        conn.doClientHandShake(
            path = "/updateReports?agent=$AGENT",
            host = "$HOST:$PORT",
        )

        while (true) {
            val event = try {
                ws.readMessage()
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
    val ws = conn.asWsClientConnection()

    try {
        conn.doClientHandShake(
            path = "/getCaseCount",
            host = "$HOST:$PORT",
        )

        while (true) {
            when (val event = ws.readMessage()) {
                WebSocketEvent.Close -> break

                is WebSocketEvent.Text -> {
                    return event.text.trim().toInt()
                }

                else -> {}
            }
        }

        error("failed to get case count")
    } finally {
        runCatching { ws.close() }
    }
}

internal const val CLIENT_SEC_KEY = "dGhlIHNhbXBsZSBub25jZQ=="

private suspend fun AsyncConnection.doClientHandShake(
    path: String,
    host: String,
    clientSecKey: String = CLIENT_SEC_KEY,
) {
    val conn = this
    val handShake = buildString {
        append("GET $path HTTP/1.1\r\n")
        append("Host: ${host}\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: ${clientSecKey}\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("\r\n")
    }

    conn.sink.writeString(handShake, 0, handShake.length)
    conn.sink.flush()

    val headers = conn.source.parseHeaders()
    for ((key, value) in headers.entries) {
        when (key) {
            "Sec-WebSocket-Accept" -> {
                if (calculateServerAcceptKey(clientSecKey) != value) {
                    throw IOException("Header value of Sec-WebSocket-Accept(${value}) is not matched to ${clientSecKey}.")
                }
            }
            // If there are other headers to verify...
        }
    }
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

internal fun calculateServerAcceptKey(key: String) =
    Base64.encode(sha1((key + SEC_MAGIC_KEY).encodeToByteArray()))

internal fun sha1(bytes: ByteArray): ByteArray {
    return SHA1().digest(bytes)
}
internal const val SEC_MAGIC_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
