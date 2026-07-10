package kio.async

import kotlinx.io.Buffer
import kotlinx.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.WritableByteChannel

actual interface SuspendIo {
    fun attach(handle: SelectionKeyWrapper, event: PollInterest)
    fun detach(handle: SelectionKeyWrapper, event: PollInterest)

    suspend fun suspendRead(selectableChannel: SelectableChannel, channel: ReadableByteChannel, sink: Buffer, byteCount: Long): Long
    suspend fun suspendWrite(selectableChannel: SelectableChannel, channel: WritableByteChannel, source: Buffer, byteCount: Long): Long

    suspend fun suspendConnect(selectableChannel: SelectableChannel)
    suspend fun suspendAccept(selectableChannel: SelectableChannel)
}

interface SuspendChannelIo : SuspendIo {
    suspend fun awaitIo(handle: SelectionKeyWrapper, interest: PollInterest)

    override suspend fun suspendRead(selectableChannel: SelectableChannel, channel: ReadableByteChannel, sink: Buffer, byteCount: Long): Long {
        awaitIo(SelectionKeyWrapper(selectableChannel), POLL_INTEREST_READ)
        val result = doRead(sink, byteCount) { byteBuffer ->
            channel.read(byteBuffer)
        }
        return result
    }

    override suspend fun suspendWrite(selectableChannel: SelectableChannel, channel: WritableByteChannel, source: Buffer, byteCount: Long): Long {
        val written = doWriteOnce(source, byteCount) { byteBuffer ->
            channel.write(byteBuffer)
        }
        if (written == 0L) {
            awaitIo(SelectionKeyWrapper(selectableChannel), POLL_INTEREST_WRITE)
        }
        return written
    }

    override suspend fun suspendConnect(selectableChannel: SelectableChannel) {
        awaitIo(SelectionKeyWrapper(selectableChannel), POLL_INTEREST_CONNECT)
    }

    override suspend fun suspendAccept(selectableChannel: SelectableChannel) {
        awaitIo(SelectionKeyWrapper(selectableChannel), POLL_INTEREST_ACCEPT)
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
