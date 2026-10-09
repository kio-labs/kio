package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlin.math.min

interface AsyncRawConnection {
    val source: AsyncRawSource
    val sink: AsyncRawSink
    suspend fun close()
}

fun AsyncRawConnection.buffered() = object : AsyncConnection {
    val delegate = this@buffered
    override val source: AsyncSource = delegate.source.buffered()
    override val sink: AsyncSink = delegate.sink.buffered()

    override suspend fun close() = delegate.close()

}

interface AsyncConnection: AsyncRawConnection {
    override val source: AsyncSource
    override val sink: AsyncSink
    override suspend fun close()
}

fun openInMemoryPipe(
    maxBufferSize: Long = 64 * 1024L,
): AsyncRawConnection {
    val pipe = AsyncMemoryPipe(maxBufferSize)
    return object : AsyncRawConnection {
        override val source: AsyncRawSource = pipe.source
        override val sink: AsyncRawSink = pipe.sink

        override suspend fun close() {
            pipe.source.close()
            pipe.sink.close()
        }
    }
}

private class AsyncMemoryPipe(
    private val maxBufferSize: Long,
) {
    init {
        require(maxBufferSize > 0)
    }

    private val mutex = Mutex()
    private val buffer = Buffer()

    private var sourceClosed = false
    private var sinkClosed = false

    private val readWaiters = ArrayDeque<CompletableDeferred<Unit>>()
    private val writeWaiters = ArrayDeque<CompletableDeferred<Unit>>()

    val source: AsyncRawSource = Source()
    val sink: AsyncRawSink = Sink()

    private inner class Source : AsyncRawSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            require(byteCount >= 0L)
            if (byteCount == 0L) return 0L

            while (true) {
                val waiter = mutex.withLock {
                    check(!sourceClosed) { "source is closed" }

                    if (buffer.size > 0L) {
                        val readByteCount = min(byteCount, buffer.size)
                        sink.write(buffer, readByteCount)

                        notifyWriters()
                        return readByteCount
                    }

                    if (sinkClosed) {
                        return -1L
                    }

                    CompletableDeferred<Unit>().also {
                        readWaiters.addLast(it)
                    }
                }

                waiter.await()
            }
        }

        override suspend fun close() {
            mutex.withLock {
                if (sourceClosed) return
                sourceClosed = true

                notifyWriters()
                notifyReaders()
            }
        }
    }

    private inner class Sink : AsyncRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            require(byteCount >= 0L)
            require(source.size >= byteCount)

            var remaining = byteCount

            while (remaining > 0L) {
                val waiter = mutex.withLock {
                    check(!sinkClosed) { "sink is closed" }
                    check(!sourceClosed) { "source is closed" }

                    val writableByteCount = maxBufferSize - buffer.size

                    if (writableByteCount > 0L) {
                        val writeByteCount = min(remaining, writableByteCount)

                        buffer.write(source, writeByteCount)
                        remaining -= writeByteCount

                        notifyReaders()
                        null
                    } else {
                        CompletableDeferred<Unit>().also {
                            writeWaiters.addLast(it)
                        }
                    }
                }

                waiter?.await()
            }
        }

        override suspend fun flush() {
            // memory pipe 不需要 flush
        }

        override suspend fun close() {
            mutex.withLock {
                if (sinkClosed) return
                sinkClosed = true

                notifyReaders()
                notifyWriters()
            }
        }
    }

    private fun notifyReaders() {
        while (readWaiters.isNotEmpty()) {
            readWaiters.removeFirst().complete(Unit)
        }
    }

    private fun notifyWriters() {
        while (writeWaiters.isNotEmpty()) {
            writeWaiters.removeFirst().complete(Unit)
        }
    }
}
