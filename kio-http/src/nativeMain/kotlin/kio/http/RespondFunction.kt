package kio.http

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.charsets.Charsets
import kio.async.LimitedSource
import kio.async.inMemoryAsyncBuffer
import kotlinx.io.Buffer
import kotlinx.io.writeString

fun CallContext.respond(
    status: HttpStatusCode, message: String = ""
) {
    respondText(status = status, text = message)
}

fun CallContext.respondText(
    text: String,
    contentType: ContentType? = null,
    status: HttpStatusCode? = null,
    configure: HttpResponseHead.Builder.() -> Unit = {}
) {
    val charset = contentType?.charset() ?: Charsets.UTF_8
    require(charset == Charsets.UTF_8) {
        "Only support utf8, but get $charset."
    }

    val source = if (text.isNotEmpty()) {
        textLimitedSource(text, charset)
    } else {
        null
    }
    responseHeadBuilder.apply {
        configure()
        statusCode = status ?: HttpStatusCode.OK
        headers[HttpHeaders.ContentType] = defaultTextContentType(contentType).toString()
        headers[HttpHeaders.ContentLength] = source?.bytesRemaining?.toString() ?: "0"
    }
    responseBodySource = source

    requestHandled = true
}

private fun textLimitedSource(
    text: String,
    charset: Charset = Charsets.UTF_8,
): LimitedSource {
    require(charset == Charsets.UTF_8) {
        "Only support utf8, but get $charset."
    }
    val buffer = Buffer().apply { writeString(text) }
    return LimitedSource(
        buffer.inMemoryAsyncBuffer(),
        buffer.size
    )
}

/**
 * Creates a default [ContentType] based on the given [contentType] and current call.
 *
 * If [contentType] is `null`, it tries to fetch an already set "Content-Type" response header.
 * If the header is not available, `text/plain` is used. If [contentType] is specified, it uses it.
 *
 * Additionally, if a content type is `Text` and a charset is not set for a content type,
 * it appends `; charset=UTF-8` to the content type.
 *
 * [Report a problem](https://ktor.io/feedback/?fqname=io.ktor.server.response.defaultTextContentType)
 */
public fun HttpResponseHead.Builder.defaultTextContentType(contentType: ContentType?): ContentType {
    val result = when (contentType) {
        null -> {
            val headersContentType = headers[HttpHeaders.ContentType]
            headersContentType?.let {
                try {
                    ContentType.parse(headersContentType)
                } catch (_: BadContentTypeFormatException) {
                    null
                }
            } ?: ContentType.Text.Plain
        }

        else -> contentType
    }

    return if (result.charset() == null && result.match(ContentType.Text.Any)) {
        result.withCharset(Charsets.UTF_8)
    } else {
        result
    }
}
