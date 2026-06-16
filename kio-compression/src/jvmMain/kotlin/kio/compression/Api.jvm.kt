package kio.compression

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource

actual fun kio.async.AsyncSource.deflateSource(): kio.async.AsyncRawSource {
    TODO("Not yet implemented")
}

actual fun AsyncSink.deflateSink(level: Int): AsyncRawSink {
    TODO("Not yet implemented")
}

actual fun AsyncSource.zlibSource(): AsyncRawSource {
    TODO("Not yet implemented")
}

actual fun AsyncSink.zlibSink(level: Int): AsyncRawSink {
    TODO("Not yet implemented")
}

actual fun AsyncSource.gzipSource(): AsyncRawSource {
    TODO("Not yet implemented")
}

actual fun AsyncSink.gzipSink(level: Int): AsyncRawSink {
    TODO("Not yet implemented")
}

internal actual val DEFAULT_COMPRESSION: Int
    get() = TODO("Not yet implemented")