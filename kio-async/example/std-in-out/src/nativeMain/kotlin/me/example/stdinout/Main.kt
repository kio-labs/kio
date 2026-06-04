package me.example.stdinout

import kio.async.AsyncSource
import kio.async.asyncFdRawSink
import kio.async.asyncFdRawSource
import kio.async.buffered
import kio.async.runPollEventLoop
import kio.async.writeString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Buffer
import kotlinx.io.readString
import platform.posix.fileno
import platform.posix.stdin
import platform.posix.stdout

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop {

//    setNonBlocking(fileno(stdout))
//    setNonBlocking(fileno(stdin))

    val sink = asyncFdRawSink(fileno(stdout)).buffered()
    val source = asyncFdRawSource(fileno(stdin)).buffered()

    while (true) {
        val line = source.readLineByByte()
        sink.writeString("$line\n")
        sink.flush()
    }
}

private suspend fun AsyncSource.readLineByByte(): String {
    val bytes = Buffer()

    while (true) {
        val b =  readByte()

        if (b == '\n'.code.toByte()) {
            break
        }

        if (b != '\r'.code.toByte()) {
            bytes.writeByte(b)
        }
    }

    return bytes.readString()
}