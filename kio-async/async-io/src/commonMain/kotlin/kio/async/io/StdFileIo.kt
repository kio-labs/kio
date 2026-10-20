package kio.async.io

import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.SuspendIo

expect fun SuspendIo.stdInAsyncSource(): AsyncSource
expect fun SuspendIo.stdOutAsyncSink(): AsyncSink
expect fun SuspendIo.stdErrorAsyncSink(): AsyncSink