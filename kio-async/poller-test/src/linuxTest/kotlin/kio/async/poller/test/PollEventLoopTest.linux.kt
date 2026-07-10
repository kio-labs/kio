package kio.async.poller.test

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = EPoll
}

class UringEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = LinuxUring
}
