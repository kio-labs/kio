package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kotlinx.io.Buffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.WritableByteChannel

fun asyncChannelRawSource(
    selectable: SelectableChannel,
    channel: ReadableByteChannel,
    suspendIo: SuspendIo,
): AsyncRawSource = AsyncChannelRawSource(selectable, channel, suspendIo)

fun asyncChannelRawSink(
    selectable: SelectableChannel,
    channel: WritableByteChannel,
    suspendIo: SuspendIo,
): AsyncRawSink =
    AsyncChannelRawSink(selectable, channel, suspendIo)

private class AsyncChannelRawSource(
    private val selectable: SelectableChannel,
    private val channel: ReadableByteChannel,
    private val suspendIo: SuspendIo
) : AsyncRawSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        while (true) {
            val result = suspendIo.suspendRead(selectable, channel, sink, byteCount)

            if (result == -1L) return -1
            if (result > 0L) return result
        }
    }

    override suspend fun close() {
        channel.close()
    }
}

private class AsyncChannelRawSink(
    private val selectableChannel: SelectableChannel,
    private val channel: WritableByteChannel,
    private val suspendIo: SuspendIo
) : AsyncRawSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        var remaining = byteCount

        while (remaining > 0) {
            val written = suspendIo.suspendWrite(selectableChannel, channel, source, remaining)

            remaining -= written
        }
    }

    override suspend fun flush() {
        // No-op
    }

    override suspend fun close() {
        channel.close()
    }
}
