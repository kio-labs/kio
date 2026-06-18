package kio.http.internal

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode

internal class HttpResponseHead internal constructor(
    val version: HttpProtocolVersion,
    val status: Int,
    val statusText: String,
    val headers: Headers,
) {
    class Builder {
        // TODO: only support http 1.1 now
        var version: HttpProtocolVersion = HttpProtocolVersion.HTTP_1_1
        var statusCode: HttpStatusCode = HttpStatusCode.NotFound
        var headers: HeadersBuilder = HeadersBuilder()

        fun build(): HttpResponseHead {
            return HttpResponseHead(
                version = version,
                status = statusCode.value,
                statusText = statusCode.description,
                headers = headers.build(),
            )
        }
    }
}
