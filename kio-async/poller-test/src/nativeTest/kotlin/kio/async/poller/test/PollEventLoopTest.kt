package kio.async.poller.test

import kio.async.Poller
import kio.async.openPipe
import kio.async.poller.kqueue.Kqueue
import kio.async.poller.poll.PosixPoll
import kio.async.runPollEventLoop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PosixPollEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = PosixPoll
}

class KqueueEventLoopTest: PollEventLoopTest() {
    override val factory: Poller.Factory = Kqueue
}

abstract class PollEventLoopTest {
    abstract val factory : Poller.Factory

    @Test
    fun awaitReadIoResumeWhenPipeReadable() = runPollEventLoop(factory) {
        val (source, sink) = openPipe()
        try {
            launch {
                delay(10.milliseconds)
                sink.writeInt(1)
                sink.flush()
            }

            assertEquals(1, source.readInt())

            source.close()
            sink.close()
        } finally {
            source.close()
            sink.close()
        }
    }

    @Test
    fun awaitReadIoResumeWhenPipeReadable2() = runPollEventLoop(factory) {
        val (source, sink) = openPipe()

        try {
            val job = launch {
                assertEquals(1, source.readInt())
            }

            delay(1.milliseconds)

            sink.writeInt(1)
            sink.flush()

            job.join()

            source.close()
            sink.close()
        } finally {
            source.close()
            sink.close()
        }
    }

    @Test
    fun multipleCoroutinesResume  () = runPollEventLoop(factory) {
        val (source1, sink1) = openPipe()
        val (source2, sink2) = openPipe()

        try {
            var isSource1Resumed = false
            var isSource2Resumed = false
            val job1 = launch { source1.readInt(); isSource1Resumed = true }
            val job2 = launch { source2.readInt(); isSource2Resumed = true }

            assertFalse(isSource1Resumed)
            assertFalse(isSource2Resumed)

            // resume pipe2
            sink2.writeInt(2)
            sink2.flush()
            job2.join()
            assertFalse(isSource1Resumed)
            assertTrue(isSource2Resumed)

            // resume pipe1
            sink1.writeInt(1)
            sink1.flush()
            job1.join()
            assertTrue(isSource1Resumed)
            assertTrue(isSource2Resumed)
        } finally {
            source1.close()
            sink1.close()
            source2.close()
            sink2.close()
        }
    }

    @Test
    fun cancelReadDoesNotResumeLater() = runPollEventLoop(factory) {
        val (source, sink) = openPipe()

        try {
            var resumed = false

            val job = launch {
                source.readInt()
                resumed = true
            }

            delay(1.milliseconds)

            job.cancel()
            job.join()

            sink.writeInt(1)
            sink.flush()

            delay(1.milliseconds)

            assertFalse(resumed)
        } finally {
            source.close()
            sink.close()
        }
    }

    @Test
    fun readImmediatelyWhenPipeAlreadyReadable() = runPollEventLoop(factory) {
        val (source, sink) = openPipe()

        try {
            sink.writeInt(1)
            sink.flush()

            assertEquals(1, source.readInt())
        } finally {
            source.close()
            sink.close()
        }
    }

    @Test
    fun delayResumeInOrder() = runPollEventLoop(factory) {
        val result = mutableListOf<Int>()

        val job1 = launch {
            delay(3.milliseconds)
            result += 1
        }

        val job2 = launch {
            delay(1.milliseconds)
            result += 2
        }

        job1.join()
        job2.join()

        assertEquals(listOf(2, 1), result)
    }

    @Test
    fun readMultipleTimesFromSamePipe() = runPollEventLoop(factory) {
        val (source, sink) = openPipe()

        try {
            val job = launch {
                assertEquals(1, source.readInt())
                assertEquals(2, source.readInt())
                assertEquals(3, source.readInt())
            }

            delay(1.milliseconds)

            sink.writeInt(1)
            sink.flush()

            sink.writeInt(2)
            sink.flush()

            sink.writeInt(3)
            sink.flush()

            job.join()
        } finally {
            source.close()
            sink.close()
        }
    }
}
