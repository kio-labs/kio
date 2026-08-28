package kio.http

import kio.async.AsyncRawSource
import kio.async.SuspendIo
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual suspend fun openFileSource(path: String): AsyncRawSource {
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

internal actual suspend fun SuspendIo.getFileStatus(path: String): FileData? {
    return SystemFileSystem.metadataOrNull(Path(path))?.let {
        FileData(isRegularFile = it.isRegularFile, isDirectory = it.isRegularFile, size = it.size)
    }
}
