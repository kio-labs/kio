package kio.websocket

import kio.network.AsyncConnection

fun AsyncConnection.upgradeToWebsocket(): WsConnection = InternalWebSocket(false, this)

fun KioWsConnection(conn: AsyncConnection, isClient: Boolean): WsConnection = InternalWebSocket(
    isClient,
    conn
)