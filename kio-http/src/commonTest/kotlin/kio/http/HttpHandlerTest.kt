package kio.http

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kio.async.buffered
import kio.async.inMemoryAsyncBuffer
import kio.async.io.AsyncRawConnection
import kio.async.io.buffered
import kio.async.readString
import kio.http.internal.HttpRequestHead
import kio.http.internal.HttpResponseHead
import kio.http.internal.http1.parseResponseHead
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpHandlerTest {
    @Test
    fun smokeTest() = runTest {
        request { head, body ->
            assertEquals(HttpStatusCode.NotFound.value, head.status)
        }
    }

    @Test
    fun textResponseTest() = runTest {
        request(
            handler = {
                this.respondText("hello", status = HttpStatusCode.Found)
            }
        ) { head, body ->
            assertEquals(HttpStatusCode.Found.value, head.status)
            assertEquals("hello", body?.buffered()?.readString())
        }
    }

    @Test
    fun requestBodyTest() = runTest {
        request(
            header = {
                this[HttpHeaders.ContentLength] = "11"
            },
            body = {
                writeString("hello world")
            },
            handler = {
                assertEquals("hello world", requestBody!!.readString())
            }
        )
    }

    @Test
    fun chunkedRequestTest() = runTest {
        request(
            header = {
                this[HttpHeaders.TransferEncoding] = "chunked"
            },
            body = {
                writeString("5\r\n")
                writeString("hello\r\n")
                writeString("6\r\n")
                writeString(" world\r\n")
                writeString("0\r\n")
                writeString("\r\n")
            },
            handler = {
                respondText(requestBody!!.readString())
            }
        ) { head, body ->
            assertEquals("hello world", body?.buffered()?.readString())
        }
    }
}

internal suspend fun request(
    header: HeadersBuilder.() -> Unit = {},
    body: Buffer.() -> Unit = {},
    interceptors: List<CallInterceptor> = listOf(),
    handler: CallHandler? = null,
    assertResponse: suspend (HttpResponseHead, AsyncSource?) -> Unit = { _, _ -> }
) {
    val requestHead = HttpRequestHead(
        method = HttpMethod.Get,
        uri = "",
        version = HttpProtocolVersion.HTTP_1_1,
        headers = Headers.build(header)
    )
    val requestBody = Buffer().apply(body)

    val conn = MockConnection(requestBody)
    val bufferedConn = conn.buffered()

    doHandleHttp1Request(
        head = requestHead,
        conn = bufferedConn,
        handler = handler?.let { foldCallInterceptor(interceptors, it) },
    )

    val responseHead = conn.responseBuffer.parseResponseHead()
    assertResponse.invoke(responseHead, conn.httpResponse())
}

private class MockConnection(
    requestBody: Buffer = Buffer(),
) : AsyncRawConnection {
    val responseBuffer = Buffer().inMemoryAsyncBuffer()

    override val source: AsyncRawSource = requestBody.inMemoryAsyncBuffer()

    override val sink: AsyncRawSink = responseBuffer

    suspend fun httpResponse(): AsyncSource? = responseBuffer.takeIf { !responseBuffer.exhausted() }

    override suspend fun close() {}
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)

    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}