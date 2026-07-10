package kio.async.poller.test

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class PosixPollEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = PosixPoll
}
