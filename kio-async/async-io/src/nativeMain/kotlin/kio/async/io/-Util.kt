package kio.async.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal fun resultErrorMessage(result: Int): String {
    val error = if (result < 0) -result else result
    val errStr = strerror(error)?.toKString() ?: "Unknown errno: $error"

    return "$errStr($result)"
}
