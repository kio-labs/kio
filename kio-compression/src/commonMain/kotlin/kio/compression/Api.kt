package kio.compression

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Returns a [RawSource] that decompresses this [Source] using the raw DEFLATE algorithm.
 */
expect fun AsyncSource.deflateSource(): AsyncRawSource

/**
 * Returns a [RawSink] that compresses data to this [Sink] using the raw DEFLATE algorithm.
 */
expect fun AsyncSink.deflateSink(level: Int = DEFAULT_COMPRESSION): AsyncRawSink

/**
 * Returns a [RawSource] that zlib-decompresses this [Source].
 */
expect fun AsyncSource.zlibSource(): AsyncRawSource

/**
 * Returns a [RawSink] that zlib-compresses data to this [Sink].
 */
expect fun AsyncSink.zlibSink(level: Int = DEFAULT_COMPRESSION): AsyncRawSink

/**
 * Returns a [RawSource] that gzip-decompresses this [Source].
 */
expect fun AsyncSource.gzipSource(): AsyncRawSource

/**
 * Returns a [RawSink] that gzip-compresses data to this [Sink].
 */
expect fun AsyncSink.gzipSink(level: Int = DEFAULT_COMPRESSION): AsyncRawSink

internal expect val DEFAULT_COMPRESSION : Int

internal const val CHUNK_SIZE = 8192

