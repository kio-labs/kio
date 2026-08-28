package kio.http

import kio.async.SuspendIo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import linux.platform.AT_FDCWD
import linux.platform.STATX_BASIC_STATS
import linux.platform.statx
import platform.posix.ENOENT
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun SuspendIo.getFileStatus(path: String): FileData? = memScoped {
    val struct_stat = alloc<statx>()
    if (suspendStatx(AT_FDCWD, path, 0, STATX_BASIC_STATS, struct_stat.ptr) != 0) {
        if (errno == ENOENT) return null
        throw IOException("stat failed to ${path}: ${strerror(errno)?.toKString()}")
    }
    val mode = struct_stat.stx_mode.toInt()
    val isFile = (mode and S_IFMT) == S_IFREG
    return FileData(
        isRegularFile = isFile,
        isDirectory = (mode and S_IFMT) == S_IFDIR,
        if (isFile) struct_stat.stx_size.toLong() else -1L
    )
}