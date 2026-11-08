package kio.websocket

import kio.async.PollerFactory
import kio.async.poller.select.Select

class JvmSelectWebsocketConnectionTest: WebsocketConnectionTest() {
    override val pollerFactory: PollerFactory = Select
}