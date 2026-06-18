package kio.http

import io.ktor.http.HttpHeaders
import kio.async.buffered
import kio.async.readString
import kio.compression.gzipSource
import kio.http.internal.http1.chunked
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class EncodeDecodeTest {
    @Test
    fun compressedGzipRequestBodyTest() = runTest {
        val buffer = Buffer()
        buffer.write("1f8b0800000000000003cb48cdc9c95728cf2fca49010085114a0d0b000000".hexToByteArray())

        request(
            header = {
                this[HttpHeaders.ContentEncoding] = "Gzip"
                this[HttpHeaders.ContentLength] = buffer.size.toString()
            },
            body = {
                write(buffer, buffer.size)
            },
            interceptors = listOf(RequestBodyDecodeInterceptor),
            handler = {
                assertEquals("hello world", requestBody!!.readString())
            }
        )
    }

    @Test
    fun compressedGzipResponseTest() = runTest {
        request(
            header = {
                this[HttpHeaders.AcceptEncoding] = "Gzip"
            },
            interceptors = listOf(RespondedBodyEncodeInterceptor),
            handler = {
                respondText("Hello world")
            }
        ) { head, body ->
            assertEquals("gzip", head.headers[HttpHeaders.ContentEncoding])
            assertEquals("chunked", head.headers[HttpHeaders.TransferEncoding])
            assertEquals("Hello world", body!!.chunked().buffered().gzipSource().buffered().readString())
        }
    }
}
