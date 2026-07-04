package kio.http.internal

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode

internal class HttpResponseHead internal constructor(
    val status: Int,
    val statusText: String,
    val headers: Headers,
) {
    class Builder {
        var statusCode: HttpStatusCode = HttpStatusCode.NotFound
        val headers: HeadersBuilder = HeadersBuilder()

        fun build(): HttpResponseHead {
            return HttpResponseHead(
                status = statusCode.value,
                statusText = statusCode.description,
                headers = headers.build(),
            )
        }
    }
}
