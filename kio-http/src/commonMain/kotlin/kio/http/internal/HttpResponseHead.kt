package kio.http.internal

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import kio.http.internal.http2.Header

internal class HttpResponseHead internal constructor(
    val status: Int,
    val statusText: String,
    val headers: Headers,
) {
    class Builder {
        var statusCode: HttpStatusCode = HttpStatusCode.NotFound
        var headers: HeadersBuilder = HeadersBuilder()

        fun build(): HttpResponseHead {
            return HttpResponseHead(
                status = statusCode.value,
                statusText = statusCode.description,
                headers = headers.build(),
            )
        }
    }
}
