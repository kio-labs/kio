package kio.async

import kotlinx.io.Buffer

internal const val SEGMENT_SIZE = 8192

internal fun checkBounds(size: Int, startIndex: Int, endIndex: Int) =
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

internal fun checkByteCount(byteCount: Long) {
    require(byteCount >= 0) { "byteCount ($byteCount) < 0" }
}

internal fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset < 0 || offset > size || size - offset < byteCount || byteCount < 0) {
        throw IllegalArgumentException(
            "offset ($offset) and byteCount ($byteCount) are not within the range [0..size($size))"
        )
    }
}

internal fun minOf(a: Long, b: Int): Long = minOf(a, b.toLong())

// Syntactic sugar.
internal fun minOf(a: Int, b: Long): Long = minOf(a.toLong(), b)

internal fun Buffer.completeSegmentByteCount(): Long {
    return size - size % SEGMENT_SIZE
}