package kio.http.internal.http2

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class PollHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: PollerFactory = PosixPoll
}