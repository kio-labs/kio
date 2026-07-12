package kio.async.io

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class PosixPollEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = PosixPoll
}
