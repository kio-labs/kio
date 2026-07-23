package kio.http

import io.ktor.http.HttpMethod
import kio.http.util.withHttpServerTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultExceptionHandlerTest {
    @Test
    fun smokeTest() = withHttpServerTest {
        server {
            inject(DefaultExceptionHandler) {
                get("/") { error("some err") }
            }
        }

        val responseHead = request("/", HttpMethod.Get).head
        assertEquals(500, responseHead.status)
        assertEquals("Internal Server Error", responseHead.statusText)
    }
}