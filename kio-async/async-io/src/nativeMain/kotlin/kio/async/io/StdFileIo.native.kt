package kio.async.io

import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.SuspendIo
import kio.async.buffered
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO

actual fun SuspendIo.stdOutAsyncSink(): AsyncSink {
    return asyncRawSink(STDOUT_FILENO).buffered()
}

actual fun SuspendIo.stdErrorAsyncSink(): AsyncSink {
    return asyncRawSink(STDERR_FILENO).buffered()
}

actual fun SuspendIo.stdInAsyncSource(): AsyncSource {
    return asyncRawSource(STDIN_FILENO).buffered()
}