package kio.http.internal.http1

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseHeaderValue
import kio.async.io.AsyncConnection
import kio.http.RouteScope
import kio.http.handleHttp1Request
import kio.http.handleWebsocketRequest
import kio.http.internal.HttpRequestHead
import kotlin.text.equals

internal suspend fun RouteScope.handleHttp1Connection(conn: AsyncConnection) {
    while (true) {
        val requestHead = conn.source.parseRequestHead()

        when {
            requestHead.isWebSocketUpgrade() -> {
                handleWebsocketRequest(requestHead, conn)
                // websocket closed, break the loop to close this connection.
                break
            }

            // Fall back to normal http request handle
            else -> handleHttp1Request(requestHead, conn)
        }
    }
}

private fun HttpRequestHead.isWebSocketUpgrade(): Boolean {
    val connection = parseHeaderValue(headers[HttpHeaders.Connection])
    val upgrade = headers[HttpHeaders.Upgrade]

    return method == HttpMethod.Get &&
            connection.map { it.value.lowercase() }.contains("upgrade") &&
            upgrade.equals("websocket", ignoreCase = true) &&
            headers[HttpHeaders.SecWebSocketKey] != null &&
            headers[HttpHeaders.SecWebSocketVersion] == "13"
}
