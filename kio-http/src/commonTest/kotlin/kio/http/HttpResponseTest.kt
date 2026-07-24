package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kio.async.inMemoryAsyncBuffer
import kio.async.writeString
import kio.http.util.withHttpServerTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpResponseTest {
    @Test
    fun textResponseContentLengthTest() = withHttpServerTest {
        server {
            get { it.respondText("1") }
            get("/2") { it.respondText("お") }
        }

        request("/", HttpMethod.Get).also {
            assertEquals("1", it.head.headers[HttpHeaders.ContentLength])
        }
        request("/2", HttpMethod.Get).also {
            assertEquals("お".encodeToByteArray().size.toString(), it.head.headers[HttpHeaders.ContentLength])
        }
    }

    @Test
    fun responseAsyncTest() = withHttpServerTest {
        server {
            get {
                val b = Buffer().inMemoryAsyncBuffer()
                b.writeString("Hello")
                it.responseAsync(b)
            }
        }

        assertEquals("Hello", request("/", HttpMethod.Get).textBody())
    }
}