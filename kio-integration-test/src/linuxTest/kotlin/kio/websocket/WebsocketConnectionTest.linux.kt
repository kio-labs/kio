package kio.websocket

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollWebsocketConnectionTest : WebsocketConnectionTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class UringWebsocketConnectionTest : WebsocketConnectionTest() {
    override val pollerFactory: PollerFactory = LinuxUring()
}
