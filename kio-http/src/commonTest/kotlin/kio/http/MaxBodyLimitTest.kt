package kio.http

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kio.async.readByteArray
import kio.http.util.withHttpServerTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxBodyLimitTest {
    @Test
    fun maxBodyLimitTest() = withHttpServerTest {
        server {
            inject(MaxBodyLimit(12)) {
                post { call ->
                    call.requestBody.readByteArray()
                    call.respondText("ok")
                }
            }

            val response1 = request("/", HttpMethod.Post, writeBody = { repeat(12) { writeByte(1) } })
            assertEquals(HttpStatusCode.OK, response1.code())

            val response2 = request("/", HttpMethod.Post, writeBody = { repeat(13) { writeByte(1) } })
            assertEquals(HttpStatusCode.PayloadTooLarge, response2.code())
        }
    }
}