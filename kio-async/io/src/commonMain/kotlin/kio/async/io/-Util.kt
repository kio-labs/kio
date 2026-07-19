package kio.async.io

internal fun checkOffsetAndCount(size: Long, offset: Long, byteCount: Long) {
    if (offset < 0 || offset > size || size - offset < byteCount || byteCount < 0) {
        throw IllegalArgumentException(
            "offset ($offset) and byteCount ($byteCount) are not within the range [0..size($size))"
        )
    }
}

internal fun minOf(a: Long, b: Int): Long = minOf(a, b.toLong())
