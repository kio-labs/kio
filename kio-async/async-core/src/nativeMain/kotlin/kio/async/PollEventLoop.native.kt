package kio.async

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.errno
import platform.posix.strerror
import platform.posix.timespec

@OptIn(ExperimentalForeignApi::class)
internal actual fun nowMillis(): Long = memScoped {
    val ts = alloc<timespec>()
    if (clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr) != 0) {
        val e = errno
        val msg = strerror(e)?.toKString()
        throw IOException("clock_gettime failed: errno=$e, message=$msg")
    }

    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}
