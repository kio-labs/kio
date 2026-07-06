package kio.async.poller.test

import kio.async.Poller
import kio.async.poller.epoll.EPoll

class EpollEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = EPoll
}
