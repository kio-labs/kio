package kio.http.internal.http2

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: PollerFactory = EPoll
}

class UringHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: PollerFactory = LinuxUring
}