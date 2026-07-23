package kio.http

import io.ktor.http.HttpStatusCode
import kio.async.AsyncRawSource
import kotlinx.io.Buffer
import kotlinx.io.IOException

fun MaxBodyLimit(max: Long): CallInterceptor = CallInterceptor { context, proceed ->
    context.wrapRequestSource {  src ->
        MaxLimitedSource(max, src)
    }
    try {
        proceed(context)
    } catch (e: MaxBodyLimitException) {
        if (!context.isHeaderCommit) {
            currentLoggerOrNull()?.warn("body limit reached", e)
            context.respond(HttpStatusCode.PayloadTooLarge)
        } else {
            throw e
        }
    }
}

class MaxBodyLimitException : IOException("request body too large")

private class MaxLimitedSource(
    max: Long,
    private val source: AsyncRawSource,
): AsyncRawSource {
    private var remain = max

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long
    ): Long {
        if (byteCount == 0L) return 0

        val n = source.readAtMostTo(sink, minOf(byteCount, remain + 1))
        if (n == -1L) return -1

        if (n <= remain) {
            remain -= n
            return n
        }

        throw MaxBodyLimitException()
    }

    override suspend fun close() {
        source.close()
    }
}
