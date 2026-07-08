package kio.async.poller.test

import kio.async.Poller
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = EPoll
}

class UringEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = LinuxUring
}
