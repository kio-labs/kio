package kio.websocket

import kio.async.FdAsyncConnection

fun KioWsConnection(fd: Int, isClient: Boolean): WsConnection = object : InternalWebSocket(
    isClient,
    FdAsyncConnection(fd)
) {
    override suspend fun close() {
        try {
            sendCloseEventIfNeeded()
        } catch (t: Throwable) {
            // ignore exception because in close
            println("exception when sendCloseEventIfNeeded $t")
        }

        conn.close()
    }
}