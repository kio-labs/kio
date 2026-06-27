package kio.async

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.WritableByteChannel

suspend fun awaitReadIo(channel: SelectableChannel) {
    suspendCancellableCoroutine { c ->
        val dispatcher = c.context[AsyncPollEventDispatcher]
            ?: error("not in async poll event")

        val handle = SelectionKeyWrapper(channel)
        dispatcher.registerHandle(handle, PollInterestRead, c)

        c.invokeOnCancellation {
            dispatcher.unRegisterHandle(handle, PollInterestRead)
        }
    }
}

suspend fun awaitWriteIo(channel: SelectableChannel) {
    suspendCancellableCoroutine { c ->
        val dispatcher = c.context[AsyncPollEventDispatcher]
            ?: error("not in async poll event")

        val handle = SelectionKeyWrapper(channel)
        dispatcher.registerHandle(handle, PollInterestWrite, c)

        c.invokeOnCancellation {
            dispatcher.unRegisterHandle(handle, PollInterestWrite)
        }
    }
}

suspend fun awaitConnectionIo(channel: SelectableChannel) {
    suspendCancellableCoroutine { c ->
        val dispatcher = c.context[AsyncPollEventDispatcher]
            ?: error("not in async poll event")

        val handle = SelectionKeyWrapper(channel)
        dispatcher.registerHandle(handle, PollInterestConnect, c)

        c.invokeOnCancellation {
            dispatcher.unRegisterHandle(handle, PollInterestConnect)
        }
    }
}

suspend fun awaitAcceptIo(channel: SelectableChannel) {
    suspendCancellableCoroutine { c ->
        val dispatcher = c.context[AsyncPollEventDispatcher]
            ?: error("not in async poll event")

        val handle = SelectionKeyWrapper(channel)
        dispatcher.registerHandle(handle, PollInterestAccept, c)

        c.invokeOnCancellation {
            dispatcher.unRegisterHandle(handle, PollInterestAccept)
        }
    }
}

fun asyncChannelRawSource(
    channel: ReadableByteChannel,
    selectableChannel: SelectableChannel,
): AsyncRawSource =
    AsyncChannelRawSource(channel, selectableChannel)

fun asyncChannelRawSink(
    channel: WritableByteChannel,
    selectableChannel: SelectableChannel,
): AsyncRawSink =
    AsyncChannelRawSink(channel, selectableChannel)

private class AsyncChannelRawSource(
    private val channel: ReadableByteChannel,
    private val selectableChannel: SelectableChannel,
) : AsyncRawSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        while (true) {
            val result = doRead(sink, byteCount) { byteBuffer ->
                channel.read(byteBuffer)
            }

            if (result == -1L) return -1
            if (result > 0L) return result

            awaitReadIo(selectableChannel)
        }
    }

    override suspend fun close() {
        channel.close()
    }
}

private class AsyncChannelRawSink(
    private val channel: WritableByteChannel,
    private val selectableChannel: SelectableChannel,
) : AsyncRawSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        var remaining = byteCount

        while (remaining > 0) {
            val written = doWriteOnce(source, remaining) { byteBuffer ->
                channel.write(byteBuffer)
            }

            if (written > 0) {
                remaining -= written
            } else {
                awaitWriteIo(selectableChannel)
            }
        }
    }

    override suspend fun flush() {
        // No-op
    }

    override suspend fun close() {
        channel.close()
    }
}

private fun doRead(
    sink: Buffer,
    byteCount: Long,
    read: (ByteBuffer) -> Int,
): Long {
    if (byteCount == 0L) return 0L
    require(byteCount >= 0) { "byteCount < 0: $byteCount" }

    val capacity = minOf(byteCount, 8192L).toInt()
    val byteBuffer = ByteBuffer.allocate(capacity)

    val n = try {
        read(byteBuffer)
    } catch (e: java.io.IOException) {
        throw IOException(e.message ?: "read failed", e)
    }

    if (n == -1) return -1L
    if (n == 0) return 0L

    byteBuffer.flip()

    val bytes = ByteArray(n)
    byteBuffer.get(bytes)

    sink.write(bytes)

    return n.toLong()
}

private fun doWrite(
    source: Buffer,
    byteCount: Long,
    write: (ByteBuffer) -> Int,
) {
    var remaining = byteCount

    while (remaining > 0) {
        val written = doWriteOnce(source, remaining, write)

        if (written <= 0) {
            throw IOException("reached capacity")
        }

        source.skip(written)
        remaining -= written
    }
}

private fun doWriteOnce(
    source: Buffer,
    byteCount: Long,
    write: (ByteBuffer) -> Int,
): Long {
    if (byteCount == 0L) return 0L
    require(byteCount >= 0) { "byteCount < 0: $byteCount" }

    val size = minOf(byteCount, 8192L).toInt()

    val bytes = ByteArray(size)
    val copied = source.readAtMostTo(bytes, 0, size)

    if (copied == 0) return 0L

    val byteBuffer = ByteBuffer.wrap(bytes, 0, copied)

    val n = try {
        write(byteBuffer)
    } catch (e: java.io.IOException) {
        throw IOException(e.message ?: "write failed", e)
    }

    return n.toLong()
}