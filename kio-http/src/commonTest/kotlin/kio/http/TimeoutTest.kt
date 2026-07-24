package kio.http

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kio.http.util.withHttpServerTest
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class TimeoutTest {
    @Test
    fun smokeTest() = withHttpServerTest {
        server {
            inject(Timeout(1.milliseconds)) {
                get {
                    delay(2.milliseconds)
                    it.respondText("hello")
                }
            }
        }

        assertEquals(HttpStatusCode.GatewayTimeout, request("/", HttpMethod.Get).code())
    }
}