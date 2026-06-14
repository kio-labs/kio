package kio.http

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parsing.ParseException
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.LimitedSource
import kio.async.indexOf
import kio.async.readString
import kio.async.writeString
import kotlinx.io.EOFException
import kotlinx.io.IOException

class HttpRequest(
    val head: HttpRequestHead,
    val body: LimitedSource?,
)

class HttpResponseHead internal constructor(
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

data class HttpRequestHead(
    val method: HttpMethod,
    val uri: String,
    val version: HttpProtocolVersion,
    val headers: Headers,
)

internal suspend fun AsyncSource.parseRequestHead(): HttpRequestHead {
    val httpMethod = parseHttpMethod(readStringUntilSpace())
    val url = readStringUntilSpace()
    val version = parseVersion(readCrlfLine())

    val headers = readHeaders()

    return HttpRequestHead(
        method = httpMethod,
        uri = url,
        version = version,
        headers = headers
    )
}

internal suspend fun AsyncSource.parseResponseHead(): HttpResponseHead {
    val version = parseVersion(readStringUntilSpace())
    val statusCode = parseStatusCode(readStringUntilSpace())
    val statusText = readCrlfLine()

    val headers = readHeaders()

    return HttpResponseHead(
        version = version,
        status = statusCode,
        statusText = statusText,
        headers = headers,
    )
}

internal suspend fun AsyncSink.writeResponseHead(head: HttpResponseHead) {
    writeString(head.version.toString())
    writeByte(' '.code.toByte())
    writeString(head.status.toString())
    writeByte(' '.code.toByte())
    writeString(head.statusText)
    writeString("\r\n")

    head.headers.entries().forEach { (key, values) ->
        for (value in values) {
            writeString(key)
            writeByte(':'.code.toByte())
            writeByte(' '.code.toByte())
            writeString(value)
            writeString("\r\n")
        }
    }

    writeString("\r\n")
}


internal suspend fun AsyncSource.readHeaders(): Headers = Headers.build {
    while (true) {
        val line = readCrlfLine()
        if (line.isEmpty()) {
            break
        }

        var start = 0

        // Parse header name
        var headerName: String? = null
        for (i in start until line.length) {
            val ch = line[i]
            if (ch == ':' && i != 0) {
                headerName = line.substring(0, endIndex = i)
                // skip colon
                start++
                break
            }

            if (isDelimiter(ch)) {
                parseHeaderNameFailed(line, ch)
            }

            start++
        }

        headerName ?: noColonFound(line)

        // Skip whitespace or tab.
        for (i in start until line.length) {
            val ch = line[i]
            if (!ch.isWhitespace() && ch != HTAB) break
            start++
        }

        // Parse header parse value
        var valueLastIndex = start
        for (i in start until line.length) {
            val ch = line[i]
            when (ch) {
                HTAB, ' ' -> {}
                '\r', '\n' -> characterIsNotAllowed(line, ch)
                else -> valueLastIndex = i
            }
        }
        val headerValue = line.substring(start, (valueLastIndex + 1).coerceAtMost(line.length))

        append(headerName, headerValue)
    }
}.also { headers ->
    val host = headers[HttpHeaders.Host]
    if (host != null) {
        validateHostHeader(host)
    }
}

internal const val HTAB: Char = '\u0009'

private fun noColonFound(text: String): Nothing {
    throw ParseException("No colon in HTTP header in $text")
}

private fun parseHeaderNameFailed(text: String, ch: Char): Nothing {
    if (ch == ':') {
        throw ParseException("Empty header names are not allowed as per RFC7230.")
    }
    characterIsNotAllowed(text, ch)
}

private fun characterIsNotAllowed(text: CharSequence, ch: Char): Nothing =
    throw ParseException("Character with code ${(ch.code and 0xff)} is not allowed. \n$text")

private fun isDelimiter(ch: Char): Boolean {
    return ch <= ' ' || ch in "\"(),/:;<=>?@[\\]{}"
}

private val hostForbiddenSymbols = setOf('/', '?', '#', '@')

private fun validateHostHeader(host: CharSequence) {
    if (host.endsWith(":")) {
        throw ParseException("Host header with ':' should contains port: $host")
    }

    if (host.any { hostForbiddenSymbols.contains(it) }) {
        throw ParseException("Host cannot contain any of the following symbols: $hostForbiddenSymbols")
    }
}

private val versions = listOf(HttpProtocolVersion.HTTP_1_0, HttpProtocolVersion.HTTP_1_1)

private fun parseHttpMethod(result: String): HttpMethod {
    return HttpMethod.parse(result)
}

private fun parseVersion(text: String): HttpProtocolVersion {
    val version = HttpProtocolVersion.parse(text)
    if (versions.contains(version)) {
        return version
    }

    throw ParseException("Unsupported HTTP version: $text")
}

private fun parseStatusCode(statusCodeStr: String): Int {
    var statusCode = 0
    statusCodeStr.forEach { ch ->
        if (ch in '0'..'9') {
            statusCode = statusCode * 10 + (ch - '0')
        } else {
            throw NumberFormatException("Illegal digit $ch in status code $statusCodeStr")
        }
    }

    if (statusOutOfRange(statusCode)) {
        throw ParseException("Status-code must be 3-digit. Status received: $statusCode.")
    }
    return statusCode
}

private const val HTTP_STATUS_CODE_MIN_RANGE = 100
private const val HTTP_STATUS_CODE_MAX_RANGE = 999
private fun statusOutOfRange(code: Int) =
    code < HTTP_STATUS_CODE_MIN_RANGE || code > HTTP_STATUS_CODE_MAX_RANGE


private suspend fun AsyncSource.readStringUntilSpace(): String {
    while (true) {
        val spaceIndex = indexOf(' '.code.toByte())

        if (spaceIndex != -1L) {
            if (spaceIndex == 0L) {
                skip(1)
                return ""
            }

            val string = readString(spaceIndex)
            skip(1)
            return string
        }

        val oldSize = buffer.size
        if (!request(oldSize + 1)) {
            throw EOFException("unexpected EOF while reading token")
        }
    }
}

private suspend fun AsyncSource.readCrlfLine(): String {
    while (true) {
        val lfIndex = indexOf('\n'.code.toByte())

        if (lfIndex != -1L) {
            if (lfIndex == 0L) {
                throw IOException("invalid CRLF line: LF without CR")
            }

            if (buffer[lfIndex - 1] != '\r'.code.toByte()) {
                throw IOException("invalid CRLF line: LF without CR")
            }

            val line = readString(lfIndex - 1)
            skip(2)
            return line
        }

        val oldSize = buffer.size
        if (!request(oldSize + 1)) {
            throw EOFException("unexpected EOF while reading CRLF line")
        }
    }
}
