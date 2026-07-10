package kio.async.poller.test

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueueEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = Kqueue
}
