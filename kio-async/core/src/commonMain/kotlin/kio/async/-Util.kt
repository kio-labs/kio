package kio.async

import kotlinx.io.Buffer

internal const val SEGMENT_SIZE = 8192

internal inline fun checkBounds(size: Int, startIndex: Int, endIndex: Int) =
    checkBounds(size.toLong(), startIndex.toLong(), endIndex.toLong())

internal fun checkBounds(size: Long, startIndex: Long, endIndex: Long) {
    if (startIndex < 0 || endIndex > size) {
        throw IndexOutOfBoundsException(
            "startIndex ($startIndex) and endIndex ($endIndex) are not within the range [0..size($size))"
        )
    }
    if (startIndex > endIndex) {
        throw IllegalArgumentException("startIndex ($startIndex) > endIndex ($endIndex)")
    }
}

// Syntactic sugar.
internal inline fun minOf(a: Int, b: Long): Long = minOf(a.toLong(), b)

internal fun Buffer.completeSegmentByteCount(): Long {
    return size - size % SEGMENT_SIZE
}