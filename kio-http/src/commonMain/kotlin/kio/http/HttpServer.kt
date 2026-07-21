package kio.http

import kio.async.io.AsyncConnection
import kio.async.io.AsyncRawConnection
import kio.async.io.ServerSocket
import kio.async.io.buffered
import kio.http.internal.http1.http1Connection
import kio.http.internal.http2.http2Connection
import kio.tls.SslConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.EOFException
import kotlinx.io.IOException

suspend fun httpServer(
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection = { buffered() },
    loggingBackEnd: LoggingBackEnd = ConsoleLogging,
    block: suspend Route.() -> Unit
) {
    withContext(CoroutineLoggingBackend(loggingBackEnd)) {
        val logger = loggingBackEnd.newLogger("Server")

        logger.info("start")

        val route = Route(RootSegment, ArrayDeque())
        route.block()

        logger.info("setup route complete")

        with(logger) { startHttpServer(route, serverSocket, connectionWrapper) }

        logger.info("stop")
    }
}

context(logger: Logger)
private suspend fun CoroutineScope.startHttpServer(
    route: Route,
    serverSocket: ServerSocket,
    connectionWrapper: AsyncRawConnection.() -> AsyncConnection,
) {
    while (true) {
        val raw = try {
            serverSocket.accept()
        } catch (t: IOException) {
            logger.warn("failed when accept new connection", t)
            continue
        }

        logger.info("Connection accepted")

        val conn = raw.connectionWrapper()
        launch {
            try {
                val sslConnection = conn as? SslConnection
                sslConnection?.let { ssl ->
                    logger.info("tls handshake started")
                    try {
                        ssl.handShake()
                        val fields = mapOf("alpn" to ssl.getSelectedAlpn())
                        logger.info("tls handshake completed", fields)
                    } catch (e: IOException) {
                        logger.warn("tls handshake failed", e)
                        throw e
                    }
                }

                val selectedAlpn = sslConnection?.getSelectedAlpn()
                when (selectedAlpn) {
                    "h2" -> route.http2Connection(conn)
                    else -> route.http1Connection(conn)
                }
            } catch (_: EOFException) {
                logger.trace("connection closed by peer")
            } catch (e: IOException) {
                logger.warn("connection disconnected with exception", e)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.error("connection failed with exception", t)
            } finally {
                conn.close()
                logger.info("connection closed")
            }
        }
    }
}
