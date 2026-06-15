package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import kio.async.AsyncSink
import kio.async.inMemoryAsyncBuffer
import kio.async.buffered
import kio.compression.gzipSink
import kio.compression.zlibSink
import kotlinx.io.Buffer

val RespondedBodyEncodeInterceptor = CallInterceptor { context, proceed ->
    proceed(context)
    context.encodeResponseBodyIfNeeded()
}

private interface Encoder {
    val name: String
    fun encode(source: AsyncSink): AsyncSink
}

private val GzipEncoder: Encoder = object : Encoder {
    override val name: String = "gzip"

    override fun encode(source: AsyncSink): AsyncSink =source.gzipSink().buffered()
}
private val DeflateEncoder: Encoder = object : Encoder {
    override val name: String = "deflate"

    override fun encode(source: AsyncSink): AsyncSink =  source.zlibSink().buffered()
}

private val supportedEncoders = mapOf(
    "gzip" to GzipEncoder,
    "deflate" to DeflateEncoder,
)

private suspend fun CallContext.encodeResponseBodyIfNeeded() {
    val acceptEncodingRaw = requestHead.headers[HttpHeaders.AcceptEncoding] ?: return
    val encoders = parseHeaderValue(acceptEncodingRaw)
        .filter { it.value == "*" || it.value in supportedEncoders }
        .flatMap { header ->
            when (header.value) {
                "*" -> supportedEncoders.values.map { it to header }
                else -> supportedEncoders[header.value]?.let { listOf(it to header) } ?: emptyList()
            }
        }
        .map { it.first }

    if (encoders.isEmpty()) return

    val encoder = encoders.first()

    val originalSource = responseBuilder.body ?: return

// TODO: write http chunk for compressed response
    val buffer = Buffer().inMemoryAsyncBuffer()
    val encoderSink = encoder.encode(buffer)

    originalSource.buffered().transferTo(encoderSink)
    encoderSink.flush()
    encoderSink.close()

    responseBuilder.head.headers[HttpHeaders.ContentEncoding] = encoder.name
    responseBuilder.head.headers[HttpHeaders.ContentLength] = buffer.size.toString()
    responseBuilder.body = buffer.limited(buffer.size)
}
