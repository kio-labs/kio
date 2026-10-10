package kio.async

import kotlinx.io.Buffer

fun AsyncRawSource.buffered(): AsyncSource = AsyncRealSource(this)
fun AsyncRawSink.buffered(): AsyncSink = AsyncRealSink(this)

fun emptyAsyncRawSource(): AsyncRawSource = EmptyAsyncSource

suspend fun AsyncSource.readIntLe(): Int {
    return readInt().reverseBytesCommon()
}

suspend fun AsyncSource.readLongLe(): Long {
    return readLong().reverseBytesCommon()
}

suspend fun AsyncSource.readShortLe(): Short {
    return readShort().reverseBytesCommon()
}

private val EmptyAsyncSource = object :  AsyncRawSource {
    override suspend fun close() {}
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1
}


