package me.example.httpserver

import kio.async.io.tcpBind
import kio.async.poller.poll.PosixPoll
import kio.async.runPollEventLoop
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop(PosixPoll) {
    signal(SIGPIPE, SIG_IGN)

    val serverSocket = tcpBind(HOST_IP, PORT)
    println("INFO: server (${serverSocket}) is listening to , $HOST_IP, $PORT")

    simpleServer(serverSocket)
}
