package kio.compression

import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kotlinx.io.Buffer
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

actual class FileHandle(val channel: FileChannel)

internal actual suspend fun SuspendIo.fileSize(fd: FileHandle): Long = fd.channel.size()

internal actual suspend fun SuspendIo.positionalFileSource(
    initialOffset: Long,
    fd: FileHandle,
): AsyncRawSource = PositionalFileSource(initialOffset, fd.channel)

/** Borrows the channel; the caller owns and closes it after reading all entries. */
private class PositionalFileSource(
    initialOffset: Long,
    private val channel: FileChannel,
) : AsyncRawSource {
    init {
        require(initialOffset >= 0) { "initialOffset < 0: $initialOffset" }
    }

    private var position = initialOffset
    private var closed = false
    private val bytes = ByteArray(CHUNK_SIZE)

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "closed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0

        val count = minOf(byteCount, bytes.size.toLong()).toInt()
        val read = channel.read(ByteBuffer.wrap(bytes, 0, count), position)
        if (read > 0) {
            sink.write(bytes, 0, read)
            position += read
        }
        return read.toLong()
    }

    override suspend fun close() {
        closed = true
    }
}
