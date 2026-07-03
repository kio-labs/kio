package kio.http.internal

import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion

internal data class HttpRequestHead constructor(
    val method: HttpMethod,
    val uri: String,
    val version: HttpProtocolVersion,
    val headers: Headers,
)
