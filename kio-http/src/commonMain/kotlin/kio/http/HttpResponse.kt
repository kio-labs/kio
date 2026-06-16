package kio.http

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charset
import io.ktor.utils.io.charsets.Charsets
import kio.async.AsyncSink
import kio.async.inMemoryAsyncBuffer
import kotlinx.io.Buffer
import kotlinx.io.writeString


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
internal fun CallContext.defaultTextContentType(contentType: ContentType?): ContentType {
    val result = when (contentType) {
        null -> {
            val headersContentType = responseHead.headers[HttpHeaders.ContentType]
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
