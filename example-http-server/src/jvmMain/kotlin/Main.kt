package me.example.httpserver

import kio.async.io.tcpBind
import kio.async.poller.select.Select
import kio.async.runPollEventLoop

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

fun main(): Unit = runPollEventLoop(Select) {
    val serverSocket = tcpBind(HOST_IP, PORT)
    println("INFO: server (${serverSocket}) is listening to , $HOST_IP, $PORT")

    simpleServer(serverSocket)
}
