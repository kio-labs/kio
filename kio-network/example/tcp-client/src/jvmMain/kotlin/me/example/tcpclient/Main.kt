package me.example.tcpclient

import kio.async.poller.select.Select
import kio.async.readLine
import kio.async.runPollEventLoop
import kio.async.writeString
import kio.network.buffered
import kio.network.openConnection

fun main(): Unit = runPollEventLoop(Select) {
    val conn = openConnection("www.example.com", 80).buffered()
    conn.sink.writeString(
        "GET / HTTP/1.1\r\n" +
        "Host: www.example.com\r\n" +
        "Connection: close\r\n" +
        "\r\n"
    )
    conn.sink.flush()
    while (!conn.source.exhausted()) {
        println(conn.source.readLine())
    }
    conn.close()
}