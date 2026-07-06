package kio.http.internal.http2

import kio.async.Poller
import kio.async.poller.epoll.EPoll

class EpollHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: Poller.Factory = EPoll
}