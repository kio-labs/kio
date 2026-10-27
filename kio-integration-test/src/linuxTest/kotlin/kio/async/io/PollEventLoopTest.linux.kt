package kio.async.io

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring
import kio.async.runPollEventLoop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EpollEventLoopTest : PollEventLoopTest() {
    override val factory: PollerFactory = EPoll
}

class UringEventLoopTest : PollEventLoopTest() {
    override val factory: PollerFactory = LinuxUring()

    @Test
    fun uringTaskOverflowTest() = runPollEventLoop(LinuxUring(1)) {
        val pipe = openPipe().buffered()
        val taskCount = 100
        repeat(taskCount) {
            launch {
                delay(10.milliseconds)
                pipe.sink.writeInt(1)
                pipe.sink.flush()
            }
        }

        repeat(taskCount) {
            assertEquals(1, pipe.source.readInt())
        }

        pipe.close()
    }

}

internal actual suspend fun openPipe(): AsyncRawConnection {
    return pipe(0)
}