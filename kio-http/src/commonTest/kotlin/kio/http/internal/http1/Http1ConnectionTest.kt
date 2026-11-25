package kio.http.internal.http1

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered
import kio.async.inMemoryAsyncBuffer
import kio.async.io.AsyncRawConnection
import kio.async.io.buffered
import kio.async.io.openInMemoryPipe
import kio.async.readString
import kio.async.writeString
import kio.http.CoroutineLoggingBackend
import kio.http.LogLevel
import kio.http.LoggingBackEnd
import kio.http.RootSegment
import kio.http.Route
import kio.http.get
import kio.http.post
import kio.http.respondText
import kio.http.responseAsync
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.writeString as writeBufferString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class Http1ConnectionTest {
    @Test
    fun multipleHttp11RequestsReuseConnection() = runTest {
        withConnectionTest(
            configure = {
                get("/ping") { it.respondText("pong") }
                post("/echo") {
                    val body = it.requestBody.readString()
                    it.respondText("echo:$body")
                }
                post("/drain") {
                    // Intentionally do not read requestBody
                    it.respondText("ignored")
                }
            }
        ) { client ->
            // 1. GET
            client.sink.writeString("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val resp1 = readResponse(client.source)
            assertEquals(200, resp1.status)
            assertEquals("pong", resp1.body)
            assertNull(resp1.headers[HttpHeaders.Connection])

            // 2. POST with Content-Length
            client.sink.writeString(
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello"
            )
            client.sink.flush()
            val resp2 = readResponse(client.source)
            assertEquals(200, resp2.status)
            assertEquals("echo:hello", resp2.body)

            // 3. POST with chunked + trailer, handler ignores body
            client.sink.writeString(
                "POST /drain HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "5\r\nworld\r\n0\r\nX-Trailer: val\r\n\r\n"
            )
            client.sink.flush()
            val resp3 = readResponse(client.source)
            assertEquals(200, resp3.status)
            assertEquals("ignored", resp3.body)

            // 4. 404 Not Found should still have Content-Length: 0 and keep connection alive
            client.sink.writeString("GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val resp4 = readResponse(client.source)
            assertEquals(404, resp4.status)
            assertEquals("0", resp4.headers[HttpHeaders.ContentLength])
            assertEquals("", resp4.body)

            // 5. Final GET with Connection: close
            client.sink.writeString("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            client.sink.flush()
            val resp5 = readResponse(client.source)
            assertEquals(200, resp5.status)
            assertEquals("pong", resp5.body)
            assertEquals("close", resp5.headers[HttpHeaders.Connection])

            assertTrue(client.source.exhausted())
        }
    }

    @Test
    fun streamingResponseWithoutLengthClosesConnection() = runTest {
        withConnectionTest(
            configure = {
                get("/stream") {
                    val data = Buffer().apply { writeBufferString("stream-payload") }.inMemoryAsyncBuffer()
                    it.responseAsync(data, HttpStatusCode.OK)
                }
            }
        ) { client ->
            client.sink.writeString("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val head = client.source.parseResponseHead()
            assertEquals(200, head.status)
            assertEquals("close", head.headers[HttpHeaders.Connection])
            assertEquals("stream-payload", client.source.readString())
            assertTrue(client.source.exhausted())
        }
    }

    @Test
    fun maxKeepAliveRequestsClosesConnection() = runTest {
        withConnectionTest(
            maxKeepAliveRequests = 2,
            configure = {
                get("/ping") { it.respondText("pong") }
            }
        ) { client ->
            client.sink.writeString("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val resp1 = readResponse(client.source)
            assertEquals("pong", resp1.body)
            assertNull(resp1.headers[HttpHeaders.Connection])

            client.sink.writeString("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val resp2 = readResponse(client.source)
            assertEquals("pong", resp2.body)
            assertEquals("close", resp2.headers[HttpHeaders.Connection])
            assertTrue(client.source.exhausted())
        }
    }

    @Test
    fun idleKeepAliveTimeoutClosesConnection() = runTest {
        withConnectionTest(
            keepAliveTimeout = 50.milliseconds,
            configure = {
                get("/ping") { it.respondText("pong") }
            }
        ) { client ->
            client.sink.writeString("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val resp1 = readResponse(client.source)
            assertEquals("pong", resp1.body)

            // Do not send another request; server should close after idle timeout
            assertTrue(client.source.exhausted())
        }
    }

    @Test
    fun peerCloseBetweenRequestsDoesNotFail() = runTest {
        withConnectionTest(
            configure = {
                get("/ping") { it.respondText("pong") }
            }
        ) { client ->
            client.sink.writeString("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n")
            client.sink.flush()
            val resp = readResponse(client.source)
            assertEquals("pong", resp.body)

            client.sink.close()
            assertTrue(client.source.exhausted())
        }
    }

    @Test
    fun firstRequestEofStillThrowsEof() = runTest {
        val route = Route(RootSegment, ArrayDeque())
        val (clientRaw, serverRaw) = createPipePair()
        val serverConn = serverRaw.buffered()
        clientRaw.buffered().sink.close()

        assertFailsWith<EOFException> {
            withContext(CoroutineLoggingBackend(NoOpLoggingBackEnd)) {
                route.http1Connection(serverConn)
            }
        }
        serverConn.close()
    }

    private data class ParsedResponse(
        val status: Int,
        val headers: io.ktor.http.Headers,
        val body: String,
    )

    private suspend fun readResponse(source: AsyncSource): ParsedResponse {
        val head = source.parseResponseHead()
        val body = when {
            head.headers[HttpHeaders.TransferEncoding].equals("chunked", ignoreCase = true) -> {
                source.chunked().buffered().readString()
            }
            else -> {
                val len = head.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
                if (len > 0) source.readString(len) else ""
            }
        }
        return ParsedResponse(head.status, head.headers, body)
    }

    private suspend fun withConnectionTest(
        keepAliveTimeout: Duration = Duration.INFINITE,
        maxKeepAliveRequests: Int = 1000,
        configure: suspend Route.() -> Unit,
        block: suspend (ClientHandle) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val route = Route(RootSegment, ArrayDeque())
        route.configure()

        val (clientRaw, serverRaw) = createPipePair()
        val serverConn = serverRaw.buffered()
        val clientConn = clientRaw.buffered()

        val serverJob = launch(CoroutineLoggingBackend(NoOpLoggingBackEnd)) {
            try {
                route.http1Connection(
                    conn = serverConn,
                    keepAliveTimeout = keepAliveTimeout,
                    maxKeepAliveRequests = maxKeepAliveRequests,
                )
            } finally {
                serverConn.close()
            }
        }

        try {
            block(ClientHandle(clientConn.source, clientConn.sink))
        } finally {
            clientConn.close()
            serverJob.join()
        }
    }

    private data class ClientHandle(
        val source: AsyncSource,
        val sink: AsyncSink,
    )

    private fun createPipePair(): Pair<AsyncRawConnection, AsyncRawConnection> {
        val clientToServer = openInMemoryPipe()
        val serverToClient = openInMemoryPipe()

        val clientRaw = object : AsyncRawConnection {
            override val source: AsyncRawSource = serverToClient.source
            override val sink: AsyncRawSink = clientToServer.sink
            override suspend fun close() {
                clientToServer.sink.close()
                serverToClient.source.close()
            }
        }

        val serverRaw = object : AsyncRawConnection {
            override val source: AsyncRawSource = clientToServer.source
            override val sink: AsyncRawSink = serverToClient.sink
            override suspend fun close() {
                serverToClient.sink.close()
                clientToServer.source.close()
            }
        }

        return clientRaw to serverRaw
    }

    private object NoOpLoggingBackEnd : LoggingBackEnd {
        override suspend fun log(
            name: String,
            level: LogLevel,
            message: String,
            cause: Throwable?,
            fields: Map<String, Any?>,
        ) = Unit
    }
}
