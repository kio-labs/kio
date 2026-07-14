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
    fun smokeTest() = runPollEventLoop(pollerFactory) {
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

    @Test
    fun serverSelectAlpnTest() = runPollEventLoop(pollerFactory) {
        val server = tcpBind("127.0.0.1", 0)
        val clientJob = launch {
            val conn = openConnection("127.0.0.1", server.boundPort)
                .withClientTls(
                    "127.0.0.1",
                    listOf("http/1.1", "h2")
                )
            assertEquals(null, conn.getSelectedAlpn())
            conn.handShake()
            assertEquals("h2", conn.getSelectedAlpn())
            conn.sink.writeInt(122)
            conn.sink.flush()
        }

        val clientConn = server.accept().withServerTls(
            certificate = "server.crt".pem,
            privateKeyFile = "server.key".pem,
            supportAlpnProtocols = listOf("h2")
        )
        assertEquals(null, clientConn.getSelectedAlpn())
        clientConn.handShake()
        assertEquals("h2", clientConn.getSelectedAlpn())
        assertEquals(122, clientConn.source.readInt())
    }
}