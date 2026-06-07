package kio.websocket

import kio.network.AsyncConnection

fun KioWsConnection(conn: AsyncConnection, isClient: Boolean): WsConnection = InternalWebSocket(
    isClient,
    conn
)