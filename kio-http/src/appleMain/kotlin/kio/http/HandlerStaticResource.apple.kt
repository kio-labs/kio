package kio.http

import kio.async.SuspendIo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import platform.posix.*
import kotlinx.io.IOException
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun SuspendIo.getFileStatus(path: String): FileData? = memScoped {
    val struct_stat = alloc<stat>()
    if (suspendStat(path, struct_stat.ptr) != 0) {
        if (errno == ENOENT) return null
        throw IOException("stat failed to ${path}: ${strerror(errno)?.toKString()}")
    }
    val mode = struct_stat.st_mode.toInt()
    val isFile = (mode and S_IFMT) == S_IFREG

    return FileData(
        isRegularFile = isFile,
        isDirectory = (mode and S_IFMT) == S_IFDIR,
        if (isFile) struct_stat.st_size.toLong() else -1L
    )
}