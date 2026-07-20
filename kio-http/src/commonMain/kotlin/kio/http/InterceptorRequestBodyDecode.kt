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

private interface Decoder {
    val name: String
    fun decode(source: AsyncSource) : AsyncSource
}

private val GzipDecoder: Decoder = object : Decoder {
    override val name: String = "gzip"

    override fun decode(source: AsyncSource): AsyncSource {
        return source.gzipSource().buffered()
    }

    override fun toString(): String = name
}

private val DeflateDecoder: Decoder = object : Decoder {
    override val name: String = "deflate"

    override fun decode(source: AsyncSource): AsyncSource {
        return source.zlibSource().buffered()
    }

    override fun toString(): String = name
}

private val supportDecoder = mapOf(
    "gzip" to GzipDecoder,
    "deflate" to DeflateDecoder,
)

private suspend fun CallContext.decodeRequestBodyIfNeeded() {
    val encodingRaw = requestHeaders[HttpHeaders.ContentEncoding] ?: return

    val encoding = parseHeaderValue(encodingRaw)
    val decoders = encoding.map { encoding ->
        supportDecoder[encoding.value.lowercase()]
            ?: error("Unsupported Content-Encoding: ${encoding.value}")
    }

    currentLogger()?.debug("Decode request with decoders[${decoders}]")

    val baseSource = requestBody ?: error("no body when decode request body")
    requestBody = decoders.foldRight(baseSource) { decoder, acc ->
        decoder.decode(acc)
    }
}