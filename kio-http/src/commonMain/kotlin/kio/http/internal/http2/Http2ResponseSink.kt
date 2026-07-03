package kio.http.internal.http2

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.http.internal.HttpResponseHead
import kio.http.internal.http2.Header.Companion.RESPONSE_STATUS_UTF8
import kotlinx.io.Buffer

internal class Http2ResponseSink(
    private val streamId: Int,
    private val streamingSink: AsyncSink,
    private val head: HttpResponseHead.Builder,
    private val trailer: HeadersBuilder,
    private val connection: Http2Connection
) : AsyncRawSink {
    private val socketConnSink = connection.socketConn.sink
    private var headCommitted = false

    private suspend fun writeHeadIfNeeded() {
        if (!headCommitted) {
            headCommitted = true
            val headers = head.build()
            with(connection) {
                socketConnSink.writeHeaders(outFinished = false, streamId, headers.toHeaders())
            }
        }
    }

    override suspend fun write(source: Buffer, byteCount: Long) {
        writeHeadIfNeeded()
        streamingSink.write(source, byteCount)
    }

    override suspend fun flush() {
        writeHeadIfNeeded()
        streamingSink.flush()
    }

    override suspend fun close() {
        if (!trailer.isEmpty()) {
            with(connection) {
                val headers = buildList { addHeaders(trailer.build()) }
                socketConnSink.writeHeaders(outFinished = false, streamId, headers)
            }
        }
        streamingSink.close()
    }
}

private fun HttpResponseHead.toHeaders(): List<Header> {
    return buildList {
        add(Header(RESPONSE_STATUS_UTF8, status.toString()))
        addHeaders(headers)
    }
}

private fun MutableList<Header>.addHeaders(headers: Headers) {
    headers.forEach { name, values ->
        values.forEach { value ->
            add(Header(name, value))
        }
    }
}
