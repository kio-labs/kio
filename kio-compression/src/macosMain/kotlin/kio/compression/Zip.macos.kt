package kio.compression

import kio.async.SuspendIo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun SuspendIo.fileSize(fd: Int): Long = memScoped {
    val stat = alloc<stat>()
    val ret = suspendFstat(fd, stat.ptr)
    if (ret < 0) {
        throw IOException("stat failed to ${fd}: ${strerror(-ret)?.toKString()}")
    }

    stat.st_size
}