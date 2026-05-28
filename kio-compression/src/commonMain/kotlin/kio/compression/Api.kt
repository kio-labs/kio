package kio.compression

import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * Returns a [RawSource] that decompresses this [Source] using the raw DEFLATE algorithm.
 */
expect fun Source.deflateSource(): RawSource

/**
 * Returns a [RawSink] that compresses data to this [Sink] using the raw DEFLATE algorithm.
 */
expect fun Sink.deflateSink(level: Int = DEFAULT_COMPRESSION): RawSink

/**
 * Returns a [RawSource] that zlib-decompresses this [Source].
 */
expect fun Source.zlibSource(): RawSource

/**
 * Returns a [RawSink] that zlib-compresses data to this [Sink].
 */
expect fun Sink.zlibSink(level: Int = DEFAULT_COMPRESSION): RawSink

/**
 * Returns a [RawSource] that gzip-decompresses this [Source].
 */
expect fun Source.gzipSource(): RawSource

/**
 * Returns a [RawSink] that gzip-compresses data to this [Sink].
 */
expect fun Sink.gzipSink(level: Int = DEFAULT_COMPRESSION): RawSink

internal expect val DEFAULT_COMPRESSION : Int

