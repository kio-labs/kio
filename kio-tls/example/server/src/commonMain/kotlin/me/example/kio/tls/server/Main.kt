package me.example.kio.tls.server

import kio.async.AsyncSource
import kio.async.poller.poll.PosixPoll
import kio.async.runPollEventLoop
import kio.network.tcpBind
import kio.tls.pem
import kio.tls.withServerTls
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readString
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal


const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop(PosixPoll) {
    signal(SIGPIPE, SIG_IGN)

    val server = tcpBind(HOST_IP, PORT)

    println("INFO: server (${server}) is listening to , $HOST_IP, $PORT")

    while (true) {
        val conn = server.accept().withServerTls(
            certificate = "server.crt".pem,
            privateKeyFile = "server.key".pem,
        )

        launch {
            val source = conn.source
            val sink = conn.sink
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val read = source.readAtMostTo(buffer)
                    if (read < 0) {
                        // EOF
                        break
                    }
                    println("$read bytes read.")
                    sink.write(buffer, 0, read)
                    sink.flush()
                }
            } catch (t: IOException) {
                println("ioException: $t, ${t.printStackTrace()}")
                throw t
            } finally {
                conn.close()
            }
        }
    }
}
