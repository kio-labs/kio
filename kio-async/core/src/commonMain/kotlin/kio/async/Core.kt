package kio.async

fun AsyncRawSource.buffered(): AsyncSource = AsyncRealSource(this)
fun AsyncRawSink.buffered(): AsyncSink = AsyncRealSink(this)
