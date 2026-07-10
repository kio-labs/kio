package kio.async.poller.test

import kio.async.PollerFactory
import kio.async.poller.select.Select

class JvmPollEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = Select
}
