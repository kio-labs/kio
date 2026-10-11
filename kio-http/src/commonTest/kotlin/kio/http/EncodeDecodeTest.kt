package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kio.async.buffered
import kio.async.readString
import kio.http.util.withHttpServerTest
import kio.compression.gzipSource
import kio.http.internal.http1.chunked
import kotlin.test.Test
import kotlin.test.assertEquals

class EncodeDecodeTest {
    @Test
    fun gzipEncodedRequestTest() = withHttpServerTest {
        server {
            inject(RequestBodyDecode) {
                post {
                    it.respondText("Response: ${it.requestBody?.readString()}")
                }
            }
        }

        val getResponse = request(
            uri = "/", method = HttpMethod.Post,
            headers = { it[HttpHeaders.ContentEncoding] = "gzip" },
            writeBody = {
                write("1f8b08000000000002fff348cdc9c95728cf2fca490100529ed68b0b000000".hexToByteArray())
            }
        )
        assertEquals("Response: Hello world", getResponse.textBody())
    }

    @Test
    fun gzipDecodedResponseTest() = withHttpServerTest {
        server {
            inject(RespondedBodyEncode) {
                get {
                    it.respondText("Hello world")
                }
            }
        }

        val getResponse = request(
            uri = "/", method = HttpMethod.Get,
            headers = { it[HttpHeaders.AcceptEncoding] = "gzip" },
        )
        assertEquals("gzip", getResponse.head.headers[HttpHeaders.ContentEncoding])
        assertEquals("chunked", getResponse.head.headers[HttpHeaders.TransferEncoding])
        val decoded = getResponse.body.chunked().buffered().gzipSource().buffered()
        try {
            assertEquals("Hello world", decoded.readString())
        } finally {
            decoded.close()
        }
    }
}
