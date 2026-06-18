package kio.http.internal.http2

import kio.async.AsyncSource


internal infix fun Byte.and(mask: Int): Int = toInt() and mask

internal infix fun Short.and(mask: Int): Int = toInt() and mask

internal infix fun Int.and(mask: Long): Long = toLong() and mask

internal suspend fun AsyncSource.readMedium(): Int =
    (
            readByte() and 0xff shl 16
                    or (readByte() and 0xff shl 8)
                    or (readByte() and 0xff)
            )
