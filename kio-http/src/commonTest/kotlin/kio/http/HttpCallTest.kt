package kio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kio.async.buffered
import kio.async.readString
import kio.async.writeString
import kio.http.util.withHttpServerTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpCallTest {
    @Test
    fun multiPartTest() = withHttpServerTest {
        server {
            post("/") {
                val reader = it.receiveMultipart()
                reader.nextPart()?.also {
                    assertEquals("form-data", it.contentDisposition?.disposition)
                    assertEquals("a story", it.contentDisposition?.name)
                    assertEquals(
                        "Hi user. The snake you gave me for free ate all the birds. Please take it back ASAP.",
                        it.body.buffered().readString()
                    )
                }
                reader.nextPart()?.also {
                    assertEquals("form-data", it.contentDisposition?.disposition)
                    assertEquals("attachment", it.contentDisposition?.name)
                    assertEquals("File content goes here", it.body.buffered().readString())
                }
                assertNull(reader.nextPart())
            }
        }

        request(
            "/", HttpMethod.Post,
            headers = {
                val contentType =
                    ContentType.MultiPart.FormData
                        .withParameter("boundary", "***bbb***")
                        .withCharset(Charsets.ISO_8859_1)
                it[HttpHeaders.ContentType] = contentType.toString()
            },
            writeBody = {
                writeString(
                    buildString {
                        append("--***bbb***\r\n")
                        append("Content-Disposition: form-data; name=\"a story\"\r\n")
                        append("\r\n")
                        append(
                            "Hi user. The snake you gave me for free ate all the birds. " +
                                    "Please take it back ASAP.\r\n"
                        )
                        append("--***bbb***\r\n")
                        append("Content-Disposition: form-data; name=\"attachment\"; filename=\"original.txt\"\r\n")
                        append("Content-Type: text/plain\r\n")
                        append("\r\n")
                        append("File content goes here\r\n")
                        append("--***bbb***--\r\n")
                    }
                )
            }
        )
    }
}