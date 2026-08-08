package kio.http

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import kio.async.AsyncRawSource
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.indexOf
import kotlinx.io.readByteArray

private const val READ_CHUNK_SIZE = 8192L
private const val MAX_HEADER_LINE_SIZE = 8192L

private val CRLF = "\r\n".encodeToByteString()

class MultipartPart internal constructor(
    val headers: Headers,
    val body: AsyncRawSource,
) {
    val contentDisposition: ContentDisposition? = headers[HttpHeaders.ContentDisposition]?.let {
        ContentDisposition.parse(it)
    }
}

class MultipartReader internal constructor(
    private val source: AsyncRawSource,
    boundary: String,
) {
    internal val buffer = Buffer()

    /**
     * first boundary:
     *
     * --abc123
     */
    private val firstBoundary: ByteString =
        "--$boundary".encodeToByteString()

    /**
     * boundary after Part body :
     *
     * \r\n--abc123
     */
    internal val bodyBoundary: ByteString =
        "\r\n--$boundary".encodeToByteString()

    private var started = false
    private var finished = false

    private var currentBody: MultipartPartBodySource? = null

    /**
     * Read next multipart part.
     *
     * Returns null when final boundary has been reached.
     */
    suspend fun nextPart(): MultipartPart? {
        if (finished) {
            return null
        }

        currentBody?.discard()
        currentBody = null

        if (!started) {
            consumeFirstBoundary()

            started = true
        } else {
            consumeNextBoundary()
        }

        if (finished) {
            return null
        }

        val headers = readHeaders()

        val body = MultipartPartBodySource(
            source = source,
            sharedBuffer = buffer,
            delimiter = bodyBoundary,
        )

        currentBody = body

        return MultipartPart(
            headers = headers,
            body = body,
        )
    }

    /**
     * Consume:
     *
     * --boundary\r\n
     *
     * First version intentionally doesn't support preamble.
     */
    private suspend fun consumeFirstBoundary() {
        requireBytes(firstBoundary.size.toLong())

        if (buffer.indexOf(firstBoundary) != 0L) {
            throw IOException("Invalid multipart initial boundary")
        }

        buffer.skip(firstBoundary.size.toLong())

        consumeBoundarySuffix()
    }

    /**
     * PartBodySource stops BEFORE:
     *
     * \r\n--boundary
     *
     * so after the body is consumed the shared buffer starts with it.
     */
    private suspend fun consumeNextBoundary() {
        requireBytes(bodyBoundary.size.toLong())

        if (buffer.indexOf(bodyBoundary) != 0L) {
            throw IOException(
                "Invalid multipart boundary"
            )
        }

        buffer.skip(bodyBoundary.size.toLong())

        consumeBoundarySuffix()
    }

    /**
     * After:
     *
     * --boundary
     *
     * there are basically two cases:
     *
     * \r\n
     *
     * => another part follows
     *
     * --
     *
     * => final boundary
     */
    private suspend fun consumeBoundarySuffix() {
        requireBytes(2)

        val first = buffer.readByte()
        val second = buffer.readByte()

        when {
            first == '\r'.code.toByte() && second == '\n'.code.toByte() -> {
                // Another part follows.
            }

            first == '-'.code.toByte() && second == '-'.code.toByte() -> {
                finished = true

                /*
                 * RFC allows data after the closing boundary
                 * (epilogue).
                 *
                 * First implementation ignores it.
                 *
                 * There is commonly a trailing CRLF:
                 *
                 * --boundary--\r\n
                 *
                 * We don't need to consume it because multipart
                 * has already finished.
                 */
            }

            else -> {
                throw IOException("Invalid multipart boundary suffix")
            }
        }
    }

    /**
     * Read multipart part headers:
     *
     * Content-Disposition: ...
     * Content-Type: ...
     *
     * <empty line>
     */
    private suspend fun readHeaders(): Headers {
        val builder = HeadersBuilder()

        while (true) {
            val line = readCrlfLine()

            if (line.isEmpty()) {
                break
            }

            val colon = line.indexOf(':')

            if (colon <= 0) {
                throw IOException(
                    "Invalid multipart header: $line"
                )
            }

            val name = line
                .substring(0, colon)
                .trim()

            val value = line
                .substring(colon + 1)
                .trim()

            builder.append(name, value)
        }

        return builder.build()
    }

    /**
     * Read one CRLF terminated header line.
     *
     * CRLF itself is not returned.
     */
    private suspend fun readCrlfLine(): String {
        while (true) {
            val index = buffer.indexOf(CRLF)

            if (index >= 0L) {
                if (index > MAX_HEADER_LINE_SIZE) {
                    throw IOException(
                        "Multipart header line too long"
                    )
                }

                val bytes = buffer.readByteArray(index.toInt())

                // consume CRLF
                buffer.skip(2)

                return bytes.decodeToString()
            }

            if (buffer.size > MAX_HEADER_LINE_SIZE) {
                throw IOException(
                    "Multipart header line too long"
                )
            }

            val read = source.readAtMostTo(
                buffer,
                READ_CHUNK_SIZE,
            )

            if (read == -1L) {
                throw IOException(
                    "Unexpected EOF while reading multipart headers"
                )
            }
        }
    }

    private suspend fun requireBytes(byteCount: Long) {
        while (buffer.size < byteCount) {
            val read = source.readAtMostTo(
                buffer,
                maxOf(
                    READ_CHUNK_SIZE,
                    byteCount - buffer.size,
                ),
            )

            if (read == -1L) {
                throw IOException(
                    "Unexpected EOF in multipart body"
                )
            }
        }
    }
}

private class MultipartPartBodySource(
    private val source: AsyncRawSource,
    private val sharedBuffer: Buffer,
    private val delimiter: ByteString,
) : AsyncRawSource {

    private var exhausted = false
    private var closed = false

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) {
            "Multipart part body is closed"
        }

        require(byteCount >= 0L) {
            "byteCount must be >= 0"
        }

        if (byteCount == 0L) {
            return 0L
        }

        if (exhausted) {
            return -1L
        }

        while (true) {
            /*
             * Search:
             *
             * \r\n--boundary
             */
            val boundaryIndex =
                sharedBuffer.indexOf(delimiter)

            if (boundaryIndex >= 0L) {
                /*
                 * boundary is already at beginning:
                 *
                 * [\r\n--boundary...]
                 *
                 * Current body is finished.
                 *
                 * Important:
                 * DO NOT consume boundary here.
                 * MultipartReader.nextPart() consumes it.
                 */
                if (boundaryIndex == 0L) {
                    exhausted = true
                    return -1L
                }

                /*
                 * There is body data before boundary:
                 *
                 * [body body body][\r\n--boundary]
                 *
                 * Return only body.
                 */
                val count = minOf(
                    byteCount,
                    boundaryIndex,
                )

                return sharedBuffer.readAtMostTo(
                    sink,
                    count,
                )
            }

            /*
             * No complete delimiter was found.
             *
             * But delimiter could be split across reads:
             *
             * first read:
             *
             * image data...\r\n--abc
             *
             * second read:
             *
             * 123
             *
             * Therefore we must retain at least:
             *
             * delimiter.size - 1
             *
             * bytes.
             */
            val keep =
                delimiter.size.toLong() - 1L

            val safeCount =
                sharedBuffer.size - keep

            if (safeCount > 0L) {
                val count = minOf(
                    byteCount,
                    safeCount,
                )

                return sharedBuffer.readAtMostTo(
                    sink,
                    count,
                )
            }

            /*
             * Not enough data to determine whether the tail
             * is a boundary prefix.
             *
             * Read more from the HTTP request body.
             */
            val read = source.readAtMostTo(
                sharedBuffer,
                READ_CHUNK_SIZE,
            )

            if (read == -1L) {
                /*
                 * multipart Part must end with a boundary.
                 *
                 * EOF here means malformed multipart data.
                 */
                throw IOException(
                    "Unexpected EOF while reading multipart part body"
                )
            }
        }
    }

    suspend fun discard() {
        if (exhausted || closed) {
            return
        }

        val sink = Buffer()

        while (true) {
            sink.clear()

            val read = readAtMostTo(
                sink,
                READ_CHUNK_SIZE,
            )

            if (read == -1L) {
                return
            }
        }
    }

    override suspend fun close() {
        closed = true
    }
}
