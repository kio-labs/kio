package kio.async.poller.test

import kio.async.Poller
import kio.async.poller.select.Select

class JvmPollEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = Select
}
