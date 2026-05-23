package kio.async

import kotlinx.io.AsyncSink
import kotlinx.io.AsyncSource

expect fun openPipe(): Pair<AsyncSource, AsyncSink>