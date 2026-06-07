package me.example.tcpclient

import kio.async.readLine
import kio.async.runPollEventLoop
import kio.async.writeString
import kio.network.openConnection

fun main(): Unit = runPollEventLoop {
    val conn = openConnection("www.example.com", 80)
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