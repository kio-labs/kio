@file:OptIn(ExperimentalForeignApi::class)

package kio.compression

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.zlib.Z_DEFAULT_COMPRESSION

actual fun AsyncSource.deflateSource(): AsyncRawSource =
    InflaterSource(windowBits = WindowBits.RAW_DEFLATE, source = this)

actual fun AsyncSink.deflateSink(level: Int): AsyncRawSink =
    DeflaterSink(windowBits = WindowBits.RAW_DEFLATE, level = level, sink = this)

actual fun AsyncSource.zlibSource(): AsyncRawSource =
    InflaterSource(windowBits = WindowBits.ZLIB, source = this)

actual fun AsyncSink.zlibSink(level: Int): AsyncRawSink =
    DeflaterSink(windowBits = WindowBits.ZLIB, level = level, sink = this)

actual fun AsyncSource.gzipSource(): AsyncRawSource =
    InflaterSource(windowBits = WindowBits.GZIP, source = this)

actual fun AsyncSink.gzipSink(level: Int): AsyncRawSink =
    DeflaterSink(windowBits = WindowBits.GZIP, level = level, sink = this)

internal actual val DEFAULT_COMPRESSION: Int = Z_DEFAULT_COMPRESSION

internal object WindowBits {
    const val RAW_DEFLATE = -15
    const val ZLIB = 15
    const val GZIP = 31
}

