package kio.http

import kio.async.AsyncSink
import kio.async.SuspendIo
import kio.async.buffered
import kio.async.io.asyncRawSink
import platform.posix.STDOUT_FILENO

actual fun SuspendIo.stdOutAsyncSink(): AsyncSink {
    return asyncRawSink(STDOUT_FILENO).buffered()
}