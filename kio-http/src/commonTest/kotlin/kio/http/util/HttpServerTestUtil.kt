package kio.http.util

import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.inMemoryAsyncBuffer
import kio.async.io.AsyncConnection
import kio.async.readString
import kio.http.CallContext
import kio.http.RootSegment
import kio.http.Route
import kio.http.internal.HttpRequestHead
import kio.http.internal.HttpResponseHead
import kio.http.resolveHandler
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer

internal fun withHttpServerTest(
    block: suspend HttpServerTestScope.() -> Unit
) = runTest {
    block(HttpServerTestScope())
}

internal class HttpServerTestScope {
    private lateinit var rootRoute : Route

    suspend fun server(block: suspend Route.() -> Unit) {
        rootRoute = Route(RootSegment, ArrayDeque())
        rootRoute.block()
    }

    suspend fun request(
        uri: String,
        method: HttpMethod,
        headers: (HeadersBuilder) -> Unit = {},
        trailers: (HeadersBuilder) -> Unit = {},
        writeBody: suspend AsyncSink.() -> Unit = {}
    ) : HttpResponse {
        val (params, handler) = rootRoute.resolveHandler(uri, method)
        val buffer1 = Buffer().inMemoryAsyncBuffer()
        val (clientSource, serverSink) = buffer1 to buffer1
        val buffer2 = Buffer().inMemoryAsyncBuffer()
        val (serverSource, clientSink) = buffer2 to buffer2

        clientSink.apply { writeBody() }

        val mockServerConn = object : AsyncConnection {
            override val source: AsyncSource = serverSource
            override val sink: AsyncSink = serverSink
            override suspend fun close() = Unit
        }

        val callContext = CallContext(
            mockServerConn,
            requestHead = HttpRequestHead(
                method = method,
                uri = uri,
                version = HttpProtocolVersion.HTTP_1_1,
                headers = HeadersBuilder().apply { headers(this) }.build()
            ),
            body = mockServerConn.source,
            getRequestTrailers = { HeadersBuilder().apply { trailers(this) }.build() },
            parameters = params,
            responseSink = { _, _ ->
                mockServerConn.sink
            }
        )
        handler?.invoke(callContext)

        callContext.responseSink.flush()
        callContext.responseSink.close()

        val responseHead = callContext.responseHead.build()
        return HttpResponse(responseHead, clientSource)
    }
}

internal class HttpResponse(
    val head: HttpResponseHead,
    val body: AsyncSource
) {
    fun code(): HttpStatusCode {
        return HttpStatusCode(head.status, head.statusText)
    }
    suspend fun textBody(): String {
        return body.readString()
    }
}
