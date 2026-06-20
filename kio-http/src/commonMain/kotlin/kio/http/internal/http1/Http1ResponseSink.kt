package kio.http.internal.http1

import io.ktor.http.HttpProtocolVersion
import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.async.writeString
import kio.http.internal.HttpResponseHead
import kotlinx.io.Buffer

internal fun AsyncSink.http1ResponseSink(head: HttpResponseHead.Builder): AsyncRawSink =
    Http1ResponseSink(head, this)

private class Http1ResponseSink(
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

private suspend fun AsyncSink.writeResponseHead(head: HttpResponseHead) {
    writeString(HttpProtocolVersion.HTTP_1_1.toString())
    writeByte(' '.code.toByte())
    writeString(head.status.toString())
    writeByte(' '.code.toByte())
    writeString(head.statusText)
    writeString("\r\n")

    head.headers.entries().forEach { (key, values) ->
        for (value in values) {
            writeString(key)
            writeByte(':'.code.toByte())
            writeByte(' '.code.toByte())
            writeString(value)
            writeString("\r\n")
        }
    }

    writeString("\r\n")
}
