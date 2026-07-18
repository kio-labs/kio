package kio.http

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kio.http.util.withHttpServerTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HttpRouteTest {
    @Test
    fun testRoutingOnGETFooBar() = withHttpServerTest {
        server {
            get("/foo/bar") {
                it.respond(HttpStatusCode.OK)
            }
        }

        val getResponse = request(uri = "/foo/bar", method = HttpMethod.Get)
        assertEquals(HttpStatusCode.OK.value, getResponse.head.status)

        val postResponse = request(uri = "/foo/bar", method = HttpMethod.Post)
        assertFalse(postResponse.code().isSuccess())
    }

    @Test
    fun testRoutingOnGETWithParameter() = withHttpServerTest {
        server {
            route("user") {
                get {
                    it.respondText("name=${it.parameters.get("name")}")
                }
            }
        }

        val r = request(uri = "/user?name=john", method = HttpMethod.Get)
        assertEquals("name=john", r.textBody())
    }

    @Test
    fun testRoutingOnGETWithSurroundedParameter() = withHttpServerTest {
        server {
            get("/user-{name}-suffix") {
                it.respondText("name=${it.parameters.get("name")}")
            }
        }

        val r = request(uri = "/user-john-suffix", method = HttpMethod.Get)
        assertEquals("name=john", r.textBody())
    }

    @Test
    fun testRoutingOnGETWithTailcardParameter() = withHttpServerTest {
        server {
            route("/user") {
                get("{path...}") {
                    it.respondText("path=${it.parameters.getAll("path")}")
                }
            }
        }

        assertEquals("path=[]", request(uri = "/user", method = HttpMethod.Get).textBody())
        assertEquals("path=[]", request(uri = "/user/", method = HttpMethod.Get).textBody())
        assertEquals("path=[a]", request(uri = "/user/a", method = HttpMethod.Get).textBody())
        assertEquals("path=[a, b]", request(uri = "/user/a/b", method = HttpMethod.Get).textBody())
    }
}