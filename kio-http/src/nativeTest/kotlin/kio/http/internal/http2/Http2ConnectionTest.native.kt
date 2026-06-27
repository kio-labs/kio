package kio.http.internal.http2

import kio.async.Poller
import kio.async.poller.poll.PosixPoll

class PollHttp2ConnectionTest: Http2ConnectionTest() {
    override val poller: Poller.Factory = PosixPoll
}