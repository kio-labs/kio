package kio.websocket

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueueWebsocketConnectionTest: WebsocketConnectionTest() {
    override val pollerFactory: PollerFactory = Kqueue
}