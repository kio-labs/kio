package kio.http

import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import kio.async.AsyncSource
import kio.async.indexOf
import kio.async.readString
import kotlinx.io.EOFException
import kotlinx.io.IOException

class ParserException(message: String) : IllegalStateException(message)

class HttpResponse internal constructor(
    val version: HttpProtocolVersion,
    val status: HttpStatusCode,
    val statusText: String,
    val headers: Headers,
    val body: AsyncSource,
)

suspend fun AsyncSource.parseResponse(): HttpResponse {
    val version = readVersion()
    val statusCode = HttpStatusCode.fromValue(readStatusCode())
    val statusText = readCrlfLine()

    val headers = readHeaders()

    return HttpResponse(
        version = version,
        status = statusCode,
        statusText = statusText,
        headers = headers,
        body = this,
    )
}

internal suspend fun AsyncSource.readHeaders(): Headers = Headers.build {
    while (true) {
        val line = readCrlfLine()
        if (line.isEmpty()) {
            break
        }

        var remains = line

        // Parse header name
        var headerName: String? = null
        for (i in line.indices) {
            val ch = line[i]
            if (ch == ':' && i != 0) {
                headerName = line.substring(0, endIndex = i)
                remains = line.substring(i + 1)
                break
            }

            if (isDelimiter(ch)) {
                parseHeaderNameFailed(line, ch)
            }
        }

        headerName ?: noColonFound(line)

        // Skip whitespace or tab.
        for (i in remains.indices) {
            val ch = remains[i]
            if (!ch.isWhitespace() && ch != HTAB) {
                remains = remains.substring(i)
                break
            }
        }

        // Parse header parse value
        for (i in remains.indices) {
            val ch = remains[i]
            when (ch) {
                '\r', '\n' -> characterIsNotAllowed(line, ch)
            }
        }
        val headerValue = remains

        append(headerName, headerValue)
    }
}

internal const val HTAB: Char = '\u0009'

private fun noColonFound(text: String): Nothing {
    throw ParserException("No colon in HTTP header in $text")
}

private fun parseHeaderNameFailed(text: String, ch: Char): Nothing {
    if (ch == ':') {
        throw ParserException("Empty header names are not allowed as per RFC7230.")
    }
    characterIsNotAllowed(text, ch)
}

private fun characterIsNotAllowed(text: CharSequence, ch: Char): Nothing =
    throw ParserException("Character with code ${(ch.code and 0xff)} is not allowed. \n$text")

private fun isDelimiter(ch: Char): Boolean {
    return ch <= ' ' || ch in "\"(),/:;<=>?@[\\]{}"
}

private val versions = listOf(HttpProtocolVersion.HTTP_1_0, HttpProtocolVersion.HTTP_1_1)

private suspend fun AsyncSource.readVersion(): HttpProtocolVersion {
    val result = readStringUntilSpace()
    val version = HttpProtocolVersion.parse(result)
    if (versions.contains(version)) {
        return version
    }

    throw ParserException("Unsupported HTTP version: $result")
}

private suspend fun AsyncSource.readStatusCode(): Int {
    val statusCodeStr = readStringUntilSpace()
    var statusCode = 0
    statusCodeStr.forEach { ch ->
        if (ch in '0'..'9') {
            statusCode = statusCode * 10 + (ch - '0')
        } else {
            throw NumberFormatException("Illegal digit $ch in status code $statusCodeStr")
        }
    }

    if (statusOutOfRange(statusCode)) {
        throw ParserException("Status-code must be 3-digit. Status received: $statusCode.")
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
