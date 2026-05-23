package me.example.pipe

import kio.async.openPipe
import kio.async.runPollEventLoop
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.io.AsyncSource
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Duration.Companion.seconds

fun main() = runPollEventLoop {
    val (source, sink) = openPipe()
    val sendJob = launch {
        while (true) {
            delay(0.5.seconds)
            sink.writeString("Hello world!!\n")
            sink.flush()
        }
    }

    val receiveJob = launch {
        while (!source.exhausted()) {
            println(source.readLineByByte())
        }
        source.close()
    }

    val timer = launch {
        delay(5.seconds)
        sendJob.cancel()
        sink.flush()
        sink.close()
    }

    joinAll(timer, sendJob, receiveJob)
}


private suspend fun AsyncSource.readLineByByte(): String {
    val bytes = Buffer()

    while (true) {
        val b = readByte()

        if (b == '\n'.code.toByte()) {
            break
        }

        if (b != '\r'.code.toByte()) {
            bytes.writeByte(b)
        }
    }

    return bytes.readString()
}