package me.example.kio.tls.client

import kio.async.io.openConnection
import kio.async.poller.poll.PosixPoll
import kio.async.readLine
import kio.async.runPollEventLoop
import kio.async.writeString
import kio.tls.withClientTls

fun main() = runPollEventLoop(PosixPoll) {
    val conn = openConnection("www.example.com", 443).withClientTls("www.example.com")
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