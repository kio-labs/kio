package kio.tls

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollPgConnectionTest : TlsConnectionTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class UringPgConnectionTest : TlsConnectionTest() {
    override val pollerFactory: PollerFactory = LinuxUring
}
