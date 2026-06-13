package me.example.httpserver

import kio.async.poller.poll.PosixPoll
import kio.async.readString
import kio.async.runPollEventLoop
import kio.http.get
import kio.http.httpServer
import kio.http.post
import kio.http.respondText
import kio.network.tcpBind
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.*

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop(PosixPoll) {
    signal(SIGPIPE, SIG_IGN)

    val server = tcpBind(HOST_IP, PORT)

    println("INFO: server (${server}) is listening to , $HOST_IP, $PORT")

    httpServer(server) {
        get("/hello") { call ->
            call.respondText("hello back")
        }

        post("/hello") { call ->
            val requestBody = call.requestBody?.readString() ?: "no data"
            call.respondText("hello back. requestBody: $requestBody")
        }
    }
}
