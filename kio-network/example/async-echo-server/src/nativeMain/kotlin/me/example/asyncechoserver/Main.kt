package me.example.asyncechoserver

import kio.async.runPollEventLoop
import kio.network.tcpBind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import platform.posix.*

const val HOST_IP = "127.0.0.1"
const val PORT = 7878

@OptIn(ExperimentalForeignApi::class)
fun main(): Unit = runPollEventLoop {
    signal(SIGPIPE, SIG_IGN)

    val server = tcpBind(HOST_IP, PORT)

    println("INFO: server (${server}) is listening to , $HOST_IP, $PORT")

    while (true) {
        val conn = server.accept()

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
