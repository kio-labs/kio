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

// Syntactic sugar.
internal fun minOf(a: Int, b: Long): Long = minOf(a.toLong(), b)

internal fun Buffer.completeSegmentByteCount(): Long {
    return size - size % SEGMENT_SIZE
}


internal inline fun Short.reverseBytesCommon(): Short {
    val i = toInt() and 0xffff
    val reversed = (i and 0xff00 ushr 8) or
            (i and 0x00ff shl 8)
    return reversed.toShort()
}

internal inline fun Int.reverseBytesCommon(): Int {
    return (this and -0x1000000 ushr 24) or
            (this and 0x00ff0000 ushr 8) or
            (this and 0x0000ff00 shl 8) or
            (this and 0x000000ff shl 24)
}

internal inline fun Long.reverseBytesCommon(): Long {
    return (this and -0x100000000000000L ushr 56) or
            (this and 0x00ff000000000000L ushr 40) or
            (this and 0x0000ff0000000000L ushr 24) or
            (this and 0x000000ff00000000L ushr 8) or
            (this and 0x00000000ff000000L shl 8) or
            (this and 0x0000000000ff0000L shl 24) or
            (this and 0x000000000000ff00L shl 40) or
            (this and 0x00000000000000ffL shl 56)
}
