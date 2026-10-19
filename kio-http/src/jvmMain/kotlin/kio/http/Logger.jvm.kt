package kio.http

import kio.async.AsyncRawSink
import kio.async.AsyncSink
import kio.async.SuspendIo
import kio.async.buffered
import kotlinx.io.Buffer
import kotlinx.io.asSink

actual fun SuspendIo.stdOutAsyncSink(): AsyncSink {
    val sink = System.out.asSink()

    return object : AsyncRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            sink.write(source, byteCount)
        }

        override suspend fun flush() {
            sink.flush()
        }

        override suspend fun close() {
            sink.flush()
        }
    }.buffered()
}

actual fun SuspendIo.stdErrorAsyncSink(): AsyncSink {
    val sink = System.err.asSink()

    return object : AsyncRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            sink.write(source, byteCount)
        }

        override suspend fun flush() {
            sink.flush()
        }

        override suspend fun close() {
            sink.flush()
        }
    }.buffered()
}