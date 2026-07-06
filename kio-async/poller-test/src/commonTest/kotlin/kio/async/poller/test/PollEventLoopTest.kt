package kio.async.poller.test

import kio.async.Poller
import kio.async.io.buffered
import kio.async.io.openPipe
import kio.async.runPollEventLoop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

abstract class PollEventLoopTest {
    abstract val factory : Poller.Factory

    @Test
    fun awaitReadIoResumeWhenPipeReadable() = runPollEventLoop(factory) {
        val pipe = openPipe().buffered()
        val sink = pipe.sink
        val source = pipe.source
        try {
            launch {
                delay(10.milliseconds)
                sink.writeInt(1)
                sink.flush()
            }

            assertEquals(1, source.readInt())
        } finally {
            pipe.close()
        }
    }

    @Test
    fun awaitReadIoResumeWhenPipeReadable2() = runPollEventLoop(factory) {
        val pipe = openPipe().buffered()
        val sink = pipe.sink
        val source = pipe.source
        try {
            val job = launch {
                assertEquals(1, source.readInt())
            }

            delay(1.milliseconds)

            sink.writeInt(1)
            sink.flush()

            job.join()
        } finally {
            pipe.close()
        }
    }

    @Test
    fun multipleCoroutinesResume  () = runPollEventLoop(factory) {
        val pipe1 = openPipe().buffered()
        val sink1 = pipe1.sink
        val source1 = pipe1.source
        val pipe2 = openPipe().buffered()
        val sink2 = pipe2.sink
        val source2 = pipe2.source

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
            pipe1.close()
            pipe2.close()
        }
    }

    @Test
    fun cancelReadDoesNotResumeLater() = runPollEventLoop(factory) {
        val pipe = openPipe().buffered()
        val sink = pipe.sink
        val source = pipe.source
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
            pipe.close()
        }
    }

    @Test
    fun readImmediatelyWhenPipeAlreadyReadable() = runPollEventLoop(factory) {
        val pipe = openPipe().buffered()
        val sink = pipe.sink
        val source = pipe.source
        try {
            sink.writeInt(1)
            sink.flush()

            assertEquals(1, source.readInt())
        } finally {
            pipe.close()
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
        val pipe = openPipe().buffered()
        val sink = pipe.sink
        val source = pipe.source
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
            pipe.close()
        }
    }
}
