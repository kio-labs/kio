package kio.async

import kotlinx.io.InternalIoApi

fun AsyncRawSource.buffered(): AsyncSource = AsyncRealSource(this)
fun AsyncRawSink.buffered(): AsyncSink = AsyncRealSink(this)
