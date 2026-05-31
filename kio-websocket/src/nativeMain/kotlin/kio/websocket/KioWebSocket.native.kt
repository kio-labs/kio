package kio.websocket

import kio.async.AsyncConnection
import kio.async.FdAsyncConnection

fun KioWsConnection(fd: Int, isClient: Boolean): WsConnection = InternalWebSocket(
    isClient,
    FdAsyncConnection(fd)
)

fun KioWsConnection(conn: AsyncConnection, isClient: Boolean): WsConnection = InternalWebSocket(
    isClient,
    conn
)