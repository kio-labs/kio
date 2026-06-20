package kio.http.internal

import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.http.internal.http1.writeResponseHead
import kotlinx.io.Buffer

internal fun AsyncSink.httpResponseSink(head: HttpResponseHead.Builder): AsyncRawSink =
    HttpResponseSink(head, this)

internal class HttpResponseSink(
    private val head: HttpResponseHead.Builder,
    private val sink: AsyncSink,
) : AsyncRawSink {
    private var headCommit = false

    override suspend fun write(source: Buffer, byteCount: Long) {
        if (!headCommit) {
            sink.writeResponseHead(head.build())
            headCommit = true
        }

        sink.write(source, byteCount)
    }

    override suspend fun flush() {
        if (!headCommit) {
            sink.writeResponseHead(head.build())
            headCommit = true
        }

        sink.flush()
    }

    override suspend fun close() = Unit
}