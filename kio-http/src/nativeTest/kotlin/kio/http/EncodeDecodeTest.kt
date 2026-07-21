package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kio.async.readByteArray
import kio.async.readString
import kio.http.util.withHttpServerTest
import kotlinx.io.bytestring.ByteString
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
        assertEquals(
            /**
             * 1b\r\n
             * [gzip data 27 byte]
             * \r\n
             * 0\r\n
             * \r\n
             */
            ByteString("31620d0a1f8b0800000000000013f248cdc9c95728cf2fca4901000000ffff0d0a300d0a0d0a".hexToByteArray()),
            ByteString(getResponse.body.readByteArray())
        )
    }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
