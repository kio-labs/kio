package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import kio.async.AsyncSink
import kio.async.buffered
import kio.compression.gzipSink
import kio.compression.zlibSink
import kio.http.internal.http1.chunked

val RespondedBodyEncodeInterceptor = CallInterceptor { context, proceed ->
    context.encodeResponseBodyIfNeeded()
    proceed(context)
}

private interface Encoder {
    val name: String
    fun encode(source: AsyncSink): AsyncSink
}

private val GzipEncoder: Encoder = object : Encoder {
    override val name: String = "gzip"

    override fun encode(source: AsyncSink): AsyncSink = source.gzipSink().buffered()
}
private val DeflateEncoder: Encoder = object : Encoder {
    override val name: String = "deflate"

    override fun encode(source: AsyncSink): AsyncSink = source.zlibSink().buffered()
}

private val supportedEncoders = mapOf(
    "gzip" to GzipEncoder,
    "deflate" to DeflateEncoder,
)

private suspend fun CallContext.encodeResponseBodyIfNeeded() {
    val acceptEncodingRaw = requestHeaders[HttpHeaders.AcceptEncoding] ?: return
    val encoders = parseHeaderValue(acceptEncodingRaw)
        .filter { it.value == "*" || it.value.lowercase() in supportedEncoders }
        .flatMap { header ->
            when (header.value) {
                "*" -> supportedEncoders.values.map { it to header }
                else -> supportedEncoders[header.value.lowercase()]?.let { listOf(it to header) } ?: emptyList()
            }
        }
        .map { it.first }

    if (encoders.isEmpty()) return

    val encoder = encoders.first()

    currentLogger()?.debug("Encode response with encoder[${encoder.name}]")

    // Always write compressed data by chunk in HTTP/1
    responseHead.headers.remove(HttpHeaders.ContentLength)
    responseHead.headers[HttpHeaders.TransferEncoding] = "chunked"
    wrapResponseSink { chunked().buffered()}
    responseHead.headers[HttpHeaders.ContentEncoding] = encoder.name
    wrapResponseSink { encoder.encode(this) }
}
