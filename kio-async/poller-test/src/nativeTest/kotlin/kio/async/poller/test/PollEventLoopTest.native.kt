package kio.async.poller.test

import kio.async.Poller
import kio.async.poller.kqueue.Kqueue
import kio.async.poller.poll.PosixPoll

class PosixPollEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = PosixPoll
}

class KqueueEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = Kqueue
}
