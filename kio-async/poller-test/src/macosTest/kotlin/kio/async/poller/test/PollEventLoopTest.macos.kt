package kio.async.poller.test

import kio.async.Poller
import kio.async.poller.kqueue.Kqueue

class KqueueEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = Kqueue
}
