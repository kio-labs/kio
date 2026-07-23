package kio.http

import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kio.async.buffered
import kio.compression.gzipSource
import kio.compression.zlibSource

val RequestBodyDecode: CallInterceptor = CallInterceptor { context, proceed ->
    context.decodeRequestBodyIfNeeded()
    proceed(context)
}

private interface Decoder {
    val name: String
    fun decode(source: AsyncRawSource) : AsyncRawSource
}

private val GzipDecoder: Decoder = object : Decoder {
    override val name: String = "gzip"

    override fun decode(source: AsyncRawSource): AsyncRawSource {
        return source.buffered().gzipSource()
    }

    override fun toString(): String = name
}

private val DeflateDecoder: Decoder = object : Decoder {
    override val name: String = "deflate"

    override fun decode(source: AsyncRawSource): AsyncRawSource {
        return source.buffered().zlibSource()
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

    currentLoggerOrNull()?.trace("Decode request with decoders[${decoders}]")

    wrapRequestSource { src ->
        decoders.foldRight(src) { decoder, acc ->
            decoder.decode(acc)
        }
    }
}