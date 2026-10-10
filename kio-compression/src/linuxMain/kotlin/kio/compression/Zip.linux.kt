package kio.compression

import kio.async.SuspendIo
import kotlinx.cinterop.*
import kotlinx.io.IOException
import linux.platform.STATX_BASIC_STATS
import linux.platform.statx
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun SuspendIo.fileSize(fd: Int): Long = memScoped {
    val statx = alloc<statx>()
    val ret = suspendStatx(fd, "", 0, STATX_BASIC_STATS, statx.ptr)
    if (ret < 0) {
        throw IOException("stat failed to ${fd}: ${strerror(-ret)?.toKString()}")
    }

    statx.stx_size.toLong()
}