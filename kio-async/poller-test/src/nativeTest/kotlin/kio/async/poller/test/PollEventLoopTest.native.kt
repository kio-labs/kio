package kio.async.poller.test

import kio.async.Poller
import kio.async.poller.poll.PosixPoll

class PosixPollEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = PosixPoll
}
