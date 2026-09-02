package kio.tls

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollTlsConnectionTest : TlsConnectionTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class UringTlsConnectionTest : TlsConnectionTest() {
    override val pollerFactory: PollerFactory = LinuxUring()
}
