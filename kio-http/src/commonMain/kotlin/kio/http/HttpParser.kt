package kio.http

import kio.async.AsyncSource
import kio.async.indexOf
import kio.async.readLine
import kio.async.readString

class ParserException(message: String) : IllegalStateException(message)

class HttpResponse internal constructor(
    val version: CharSequence,
    val status: Int,
    val statusText: CharSequence,
)

suspend fun AsyncSource.parseResponse(): HttpResponse {
    val version = readVersion()
    val statusCode = readStatusCode()
    val statusText = readLine() ?: throw ParserException("no status text.")

    return HttpResponse(
        version,
        status = statusCode,
        statusText = statusText
    )
}

private val versions = listOf("HTTP/1.0", "HTTP/1.1")

private suspend fun AsyncSource.readVersion() : String {
    val result = readStringUntilSpace() ?: throw ParserException("no http version.")
    if (versions.contains(result)) {
        return result
    }

    throw ParserException("Unsupported HTTP version: $result")
}

private suspend fun AsyncSource.readStatusCode(): Int {
    val statusCodeStr = readStringUntilSpace() ?: throw ParserException("no status code.")
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


private suspend fun AsyncSource.readStringUntilSpace(): String? {
    if (!request(1)) return null

    return when (val spaceIndex = this.indexOf(' '.code.toByte())) {
        -1L -> null
        0L -> {
            skip(1)
            ""
        }

        else -> {
            val string = readString(spaceIndex)
            skip(1)
            string
        }
    }
}
