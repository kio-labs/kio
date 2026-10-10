package kio.compression

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun AsyncSource.deflateSource(): AsyncRawSource =
    InflaterSource(this, Inflater(true))

actual fun AsyncSink.deflateSink(level: Int): AsyncRawSink =
    DeflaterSink(this, Deflater(level, true))

actual fun AsyncSource.zlibSource(): AsyncRawSource =
    InflaterSource(this, Inflater(false))

actual fun AsyncSink.zlibSink(level: Int): AsyncRawSink =
    DeflaterSink(this, Deflater(level, false))

actual fun AsyncSource.gzipSource(): AsyncRawSource = GzipSource(this)

actual fun AsyncSink.gzipSink(level: Int): AsyncRawSink =
    DeflaterSink(this, Deflater(level, true), gzip = true)

internal actual val DEFAULT_COMPRESSION: Int = Deflater.DEFAULT_COMPRESSION
