package kio.async

import kotlinx.io.Buffer

fun AsyncRawSource.buffered(): AsyncSource = AsyncRealSource(this)
fun AsyncRawSink.buffered(): AsyncSink = AsyncRealSink(this)

fun emptyAsyncRawSource(): AsyncRawSource = EmptyAsyncSource

private val EmptyAsyncSource = object :  AsyncRawSource {
    override suspend fun close() {}
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1
}

