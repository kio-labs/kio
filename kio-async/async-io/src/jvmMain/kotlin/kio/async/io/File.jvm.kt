package kio.async.io

import kio.async.AsyncRawSource
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

actual suspend fun openFileSource(path: String): AsyncRawSource {
    val source = SystemFileSystem.source(Path(path))

    return object : AsyncRawSource {
        override suspend fun close() {
            source.close()
        }

        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            return source.readAtMostTo(sink, byteCount)
        }
    }
}