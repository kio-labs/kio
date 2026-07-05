package me.example.asyncechoserver

import kio.async.io.buffered
import kio.async.io.tcpBind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.IOException

suspend fun CoroutineScope.server(host: String, port: Int) {
    val server = tcpBind(host, port)

    println("INFO: server (${server}) is listening to , $host, $port")

    while (true) {
        val conn = server.accept().buffered()

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
