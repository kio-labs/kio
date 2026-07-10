package kio.http.internal.http2

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueueHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: PollerFactory = Kqueue
}