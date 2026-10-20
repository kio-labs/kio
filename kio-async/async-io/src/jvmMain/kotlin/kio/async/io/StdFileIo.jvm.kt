package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.SuspendIo
import kio.async.buffered
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.asSource

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

actual fun SuspendIo.stdInAsyncSource(): AsyncSource {
    val source = System.`in`.asSource()
    return object : AsyncRawSource {
        override suspend fun close() {
            source.close()
        }

        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            return source.readAtMostTo(sink, byteCount)
        }
    }.buffered()
}