package kio.async.io

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll
import kio.async.runPollEventLoop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test

class PosixPollEventLoopTest: PollEventLoopTest() {
    override val factory: PollerFactory = PosixPoll


    @Test
    fun waitIdWithIoUring() = runPollEventLoop(factory) {

    }
}
