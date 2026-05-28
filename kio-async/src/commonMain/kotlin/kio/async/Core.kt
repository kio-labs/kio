package kio.async

import kotlinx.io.InternalIoApi

@OptIn(InternalIoApi::class)
fun AsyncRawSource.buffered(): AsyncSource = AsyncRealSource(this)
@OptIn(InternalIoApi::class)
fun AsyncRawSink.buffered(): AsyncRealSink = AsyncRealSink(this)
