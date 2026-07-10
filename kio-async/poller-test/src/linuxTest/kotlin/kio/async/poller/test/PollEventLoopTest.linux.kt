package kio.async.poller.test

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll

class EpollEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = EPoll
}
