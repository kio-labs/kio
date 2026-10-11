package kio.http.internal.http1

import io.ktor.http.HttpHeaders
import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.async.buffered
import kio.async.writeString
import kio.http.CallContext
import kotlinx.io.Buffer

internal fun CallContext.wrapChunkedResponseSink() {
    // Always write compressed data by chunk in HTTP/1
    responseHead.headers.remove(HttpHeaders.ContentLength)
    responseHead.headers[HttpHeaders.TransferEncoding] = "chunked"
    wrapResponseSink { src -> ChunkedSink(src).buffered() }
}

internal class ChunkedSink(
    val sink: AsyncSink
): AsyncRawSink {
    private val buffer = Buffer()
    private var closed = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        check(!closed) { "closed" }
        buffer.write(source, byteCount)

        val byteCount = buffer.completeSegmentByteCount()
        if (byteCount > 0L) flush()
    }

    override suspend fun flush() {
        check(!closed) { "closed" }
        writeBufferedChunk()
        sink.flush()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        writeBufferedChunk()
        sink.writeString("0\r\n\r\n")
        sink.flush()
    }

    private suspend fun writeBufferedChunk() {
        if (buffer.size == 0L) return
        sink.writeString(buffer.size.toString(16))
        sink.writeString("\r\n")
        sink.write(buffer, buffer.size)
        sink.writeString("\r\n")
    }
}

internal const val SEGMENT_SIZE = 8192

internal fun Buffer.completeSegmentByteCount(): Long {
    return size - size % SEGMENT_SIZE
}