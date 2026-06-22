package me.example.httpserver

import kio.async.poller.poll.PosixPoll
import kio.async.readString
import kio.async.runPollEventLoop
import kio.http.RequestBodyDecodeInterceptor
import kio.http.RespondedBodyEncodeInterceptor
import kio.http.httpServer
import kio.http.inject
import kio.http.post
import kio.http.respondText
import kio.http.sendBinary
import kio.http.sendText
import kio.http.websocket
import kio.network.tcpBind
import kio.websocket.WebSocketEvent
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop(PosixPoll) {
    signal(SIGPIPE, SIG_IGN)

    val server = tcpBind(HOST_IP, PORT)

    println("INFO: server (${server}) is listening to , $HOST_IP, $PORT")

    httpServer(server) {
        inject(RequestBodyDecodeInterceptor) {
            inject(RespondedBodyEncodeInterceptor) {
                post("/hello") { call ->
                    val requestBody = call.requestBody?.readString() ?: "no data"
                    call.respondText("hello back. requestBody $requestBody \n")
                }

                post("/chunk") { call ->
                    call.respondText("response: ${call.requestBody?.readString()}")
                }

                websocket("/") { webSocket ->
                    for (msg in webSocket.incoming) {
                        when (msg) {
                            is WebSocketEvent.Binary -> webSocket.sendBinary(buffer = msg.buffer)
                            is WebSocketEvent.Text -> webSocket.sendText(msg.text)
                        }
                    }
                }
            }
        }
    }
}
