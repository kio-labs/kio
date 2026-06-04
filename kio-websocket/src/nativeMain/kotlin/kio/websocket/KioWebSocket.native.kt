package kio.websocket

import kio.network.AsyncConnection
import kio.network.FdAsyncConnection

fun KioWsConnection(fd: Int, isClient: Boolean): WsConnection = InternalWebSocket(
    isClient,
    FdAsyncConnection(fd)
)

fun KioWsConnection(conn: AsyncConnection, isClient: Boolean): WsConnection = InternalWebSocket(
    isClient,
    conn
)