package kio.tls

import kio.async.PollerFactory
import kio.async.io.openConnection
import kio.async.io.tcpBind
import kio.async.runPollEventLoop
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class TlsConnectionTest {
    abstract val pollerFactory: PollerFactory

    @Test
    fun tlsTest() = runPollEventLoop(pollerFactory) {
        val server = tcpBind("127.0.0.1", 0)

        val clientJob = launch {
            val conn = openConnection("127.0.0.1", server.boundPort)
                .withClientTls("127.0.0.1")
            conn.sink.writeInt(122)
            conn.sink.flush()
        }

        val clientConn = server.accept().withServerTls(
            certificate = "server.crt".pem,
            privateKeyFile = "server.key".pem,
        )
        assertEquals(122, clientConn.source.readInt())
    }
}