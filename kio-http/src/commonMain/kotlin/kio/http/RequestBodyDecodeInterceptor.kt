package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import kio.async.AsyncSource
import kio.async.buffered
import kio.compression.gzipSource
import kio.compression.zlibSource

val RequestBodyDecodeInterceptor: CallInterceptor = CallInterceptor { context, proceed ->
    context.decodeRequestBodyIfNeeded()
    proceed(context)
}

private typealias Decoder = (AsyncSource) -> AsyncSource

private val GzipDecoder: Decoder = { it.gzipSource().buffered() }
private val DeflateDecoder: Decoder = { it.zlibSource().buffered() }

private val supportDecoder = mapOf(
    "gzip" to GzipDecoder,
    "deflate" to DeflateDecoder,
)

private fun CallContext.decodeRequestBodyIfNeeded() {
    val encodingRaw = requestHeaders[HttpHeaders.ContentEncoding] ?: return

    val encoding = parseHeaderValue(encodingRaw)
    val decoders = encoding.map { encoding ->
        supportDecoder[encoding.value.lowercase()]
            ?: error("Unsupported Content-Encoding: ${encoding.value}")
    }

    val baseSource = requestBody ?: error("no body when decode request body")
    requestBody = decoders.foldRight(baseSource) { decoder, acc ->
        decoder(acc)
    }
}