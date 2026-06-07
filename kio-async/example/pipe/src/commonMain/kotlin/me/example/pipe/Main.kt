package me.example.pipe

import kio.async.AsyncSource
import kio.async.openPipe
import kio.async.poller.poll.PosixPoll
import kio.async.runPollEventLoop
import kio.async.writeString
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.time.Duration.Companion.seconds

fun main() = runPollEventLoop(PosixPoll) {
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