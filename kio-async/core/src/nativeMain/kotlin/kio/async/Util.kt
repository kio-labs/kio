package kio.async

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}

internal fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}
