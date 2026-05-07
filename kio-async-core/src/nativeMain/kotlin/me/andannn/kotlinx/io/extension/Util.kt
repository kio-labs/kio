package me.andannn.kotlinx.io.extension

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.errno
import platform.posix.strerror

internal inline fun minOf(a: Long, b: Int): Long = minOf(a, b.toLong())

internal fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset < 0 || offset > size || size - offset < byteCount || byteCount < 0) {
        throw IllegalArgumentException(
            "offset ($offset) and byteCount ($byteCount) are not within the range [0..size($size))"
        )
    }
}

internal inline fun checkByteCount(byteCount: Long) {
    require(byteCount >= 0) { "byteCount ($byteCount) < 0" }
}

@OptIn(ExperimentalForeignApi::class)
internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}
