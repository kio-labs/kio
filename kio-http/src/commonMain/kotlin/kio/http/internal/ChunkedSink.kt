package kio.http.internal

import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.async.writeString
import kotlinx.io.Buffer

internal fun AsyncSink.chunked(): AsyncRawSink = ChunkedSink(this)

internal class ChunkedSink(
    val sink: AsyncSink
): AsyncRawSink {
    private val buffer = Buffer()

    override suspend fun write(source: Buffer, byteCount: Long) {
        buffer.write(source, byteCount)

        val byteCount = buffer.completeSegmentByteCount()
        if (byteCount > 0L) flush()
    }

    override suspend fun flush() {
        sink.writeString(buffer.size.toString(16))
        sink.writeString("\r\n")
        sink.write(buffer, buffer.size)
        sink.writeString("\r\n")
        sink.flush()
    }

    override suspend fun close() {
        sink.writeString("0\r\n\r\n")
        sink.flush()
    }
}

internal const val SEGMENT_SIZE = 8192

internal fun Buffer.completeSegmentByteCount(): Long {
    return size - size % SEGMENT_SIZE
}