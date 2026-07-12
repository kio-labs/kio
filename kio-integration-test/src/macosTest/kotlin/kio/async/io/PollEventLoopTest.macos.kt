package kio.async.io

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueueEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = Kqueue
}
