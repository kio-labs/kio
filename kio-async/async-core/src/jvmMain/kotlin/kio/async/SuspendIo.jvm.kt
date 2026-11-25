package kio.async

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.WritableByteChannel

interface IoPoller {
    suspend fun awaitIo(handle: SelectionKeyWrapper, selectionKey: Int)

    fun attach(handle: SelectionKeyWrapper, selectionKey: Int)
    fun detach(handle: SelectionKeyWrapper, selectionKey: Int)
}

actual interface SuspendIo {
    suspend fun read(selectableChannel: SelectableChannel, channel: ReadableByteChannel, sink: Buffer, byteCount: Long): Long
    suspend fun write(selectableChannel: SelectableChannel, channel: WritableByteChannel, source: Buffer, byteCount: Long): Long

    suspend fun connect(selectableChannel: SelectableChannel)
    suspend fun accept(selectableChannel: SelectableChannel)
}

interface SuspendChannelIo : SuspendIo, IoPoller {

    override suspend fun read(selectableChannel: SelectableChannel, channel: ReadableByteChannel, sink: Buffer, byteCount: Long): Long {
        awaitIo(SelectionKeyWrapper(selectableChannel), SelectionKey.OP_READ)
        val result = doRead(sink, byteCount) { byteBuffer ->
            channel.read(byteBuffer)
        }
        return result
    }

    override suspend fun write(selectableChannel: SelectableChannel, channel: WritableByteChannel, source: Buffer, byteCount: Long): Long {
        val written = doWriteOnce(source, byteCount) { byteBuffer ->
            channel.write(byteBuffer)
        }
        if (written == 0L) {
            awaitIo(SelectionKeyWrapper(selectableChannel), SelectionKey.OP_WRITE)
        }
        return written
    }

    override suspend fun connect(selectableChannel: SelectableChannel) {
        awaitIo(SelectionKeyWrapper(selectableChannel), SelectionKey.OP_CONNECT)
    }

    override suspend fun accept(selectableChannel: SelectableChannel) {
        awaitIo(SelectionKeyWrapper(selectableChannel), SelectionKey.OP_ACCEPT)
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

@OptIn(UnsafeIoApi::class)
private fun doWriteOnce(
    source: Buffer,
    byteCount: Long,
    write: (ByteBuffer) -> Int,
): Long {
    if (byteCount == 0L || source.size == 0L) return 0L
    require(byteCount >= 0) { "byteCount < 0: $byteCount" }

    var bytesWritten = 0
    UnsafeBufferOperations.readFromHead(source) { data, pos, limit ->
        val toCopy = minOf(byteCount, (limit - pos).toLong()).toInt()
        val byteBuffer = ByteBuffer.wrap(data, pos, toCopy)

        bytesWritten = try {
            write(byteBuffer)
        } catch (e: java.io.IOException) {
            throw IOException(e.message ?: "write failed", e)
        }
        0
    }

    if (bytesWritten > 0) {
        source.skip(bytesWritten.toLong())
    }

    return bytesWritten.toLong()
}
