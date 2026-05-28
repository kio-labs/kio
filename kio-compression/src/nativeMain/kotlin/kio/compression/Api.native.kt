@file:OptIn(ExperimentalForeignApi::class)

package kio.compression

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import platform.zlib.Z_DEFAULT_COMPRESSION

actual fun Source.deflateSource(): RawSource =
    InflaterSource(windowBits = WindowBits.RAW_DEFLATE, source = this)

actual fun Sink.deflateSink(level: Int): RawSink =
    DeflaterSink(windowBits = WindowBits.RAW_DEFLATE, level = level, sink = this)

actual fun Source.zlibSource(): RawSource =
    InflaterSource(windowBits = WindowBits.ZLIB, source = this)

actual fun Sink.zlibSink(level: Int): RawSink =
    DeflaterSink(windowBits = WindowBits.ZLIB, level = level, sink = this)

actual fun Source.gzipSource(): RawSource =
    InflaterSource(windowBits = WindowBits.GZIP, source = this)

actual fun Sink.gzipSink(level: Int): RawSink =
    DeflaterSink(windowBits = WindowBits.GZIP, level = level, sink = this)

internal actual val DEFAULT_COMPRESSION: Int = Z_DEFAULT_COMPRESSION

internal object WindowBits {
    const val RAW_DEFLATE = -15
    const val ZLIB = 15
    const val GZIP = 31
}

internal const val CHUNK_SIZE = 8192