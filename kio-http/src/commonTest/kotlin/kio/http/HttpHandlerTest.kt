//package kio.http
//
//import io.ktor.http.Headers
//import io.ktor.http.HeadersBuilder
//import io.ktor.http.HttpHeaders
//import io.ktor.http.HttpMethod
//import io.ktor.http.HttpProtocolVersion
//import io.ktor.http.HttpStatusCode
//import kio.async.AsyncRawSink
//import kio.async.AsyncRawSource
//import kio.async.buffered
//import kio.async.inMemoryAsyncBuffer
//import kio.async.readString
//import kio.network.AsyncRawConnection
//import kio.network.buffered
//import kotlinx.coroutines.test.runTest
//import kotlinx.io.Buffer
//import kotlinx.io.writeString
//import kotlin.test.Test
//import kotlin.test.assertEquals
//
//class HttpHandlerTest {
//    @Test
//    fun smokeTest() = runTest {
//        request {
//            assertEquals(HttpStatusCode.NotFound.value, head.status)
//        }
//    }
//
//    @Test
//    fun textResponseTest() = runTest {
//        request(
//            handler = {
//                this.respondText("hello", status = HttpStatusCode.Found)
//            }
//        ) {
//            assertEquals(HttpStatusCode.Found.value, head.status)
//            assertEquals("hello", body?.buffered()?.readString())
//        }
//    }
//
//    @Test
//    fun chunkedRequestTest() = runTest {
//        request(
//            header = {
//                this[HttpHeaders.TransferEncoding] = "chunked"
//            },
//            body = {
//                writeString("5\r\n")
//                writeString("hello\r\n")
//                writeString("6\r\n")
//                writeString(" world\r\n")
//                writeString("0\r\n")
//                writeString("\r\n")
//            },
//            handler = {
//                respondText(requestBody!!.readString())
//            }
//        ) {
//            assertEquals("hello world", body?.buffered()?.readString())
//        }
//    }
//}
//
//private suspend fun request(
//    header: HeadersBuilder.() -> Unit = {},
//    body: Buffer.() -> Unit = {},
//    handler: CallHandler? = null,
//    assert: suspend HttpResponse.() -> Unit = {}
//) {
//    val requestBody = Buffer().apply(body)
//    val response = doHandleHttpRequest(
//        HttpRequestHead(
//            method = HttpMethod.Get,
//            uri = "",
//            version = HttpProtocolVersion.HTTP_1_1,
//            headers = Headers.build(header)
//        ),
//        mockConnection(requestBody).buffered(),
//        handler
//    )
//    assert(response)
//}
//
//private fun mockConnection(
//    requestBody: Buffer = Buffer(),
//): AsyncRawConnection = object : AsyncRawConnection {
//    override val source: AsyncRawSource = requestBody.inMemoryAsyncBuffer()
//    override val sink: AsyncRawSink = Buffer().inMemoryAsyncBuffer()
//
//    override suspend fun close() {}
//}