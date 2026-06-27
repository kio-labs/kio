package me.example.asyncechoserver

import kio.async.poller.select.Select
import kio.async.runPollEventLoop

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

fun main(): Unit = runPollEventLoop(Select) {
    server(HOST_IP, PORT)
}
