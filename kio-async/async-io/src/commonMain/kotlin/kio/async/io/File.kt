package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource

expect suspend fun openFileSource(path: String): AsyncRawSource
expect suspend fun openFileSink(path: String): AsyncRawSink