package kio.http.internal.http2

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll

class EpollHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: PollerFactory = EPoll
}