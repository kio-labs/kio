package kio.http.internal.http2

import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.http.internal.HttpResponseHead
import kio.http.internal.http2.Header.Companion.RESPONSE_STATUS_UTF8
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.Buffer

internal class Http2ResponseSink(
    private val streamId: Int,
    private val head: HttpResponseHead.Builder,
    private val socketConnSink: AsyncSink,
    private val writerMutex: Mutex,
    private val streamingSink: AsyncSink,
) : AsyncRawSink {
    private var headCommitted = false
// TODO: move hpack to connection
    private val hpackWriter: Hpack.Writer = Hpack.Writer()

    private suspend fun writeHeadIfNeeded() {
        if (!headCommitted) {
            headCommitted = true
            val headers = head.build()
            with(writerMutex) {
                socketConnSink.writeHeaders(
                    outFinished = false,
                    streamId,
                    headers.toHeaders(),
                    hpackWriter
                )
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
        streamingSink.close()
    }
}

private fun HttpResponseHead.toHeaders(): List<Header> {
    return buildList {
        add(Header(RESPONSE_STATUS_UTF8, status.toString()))
        headers.forEach { name, values ->
            values.forEach { value ->
                add(Header(name, value))
            }
        }
    }
}
