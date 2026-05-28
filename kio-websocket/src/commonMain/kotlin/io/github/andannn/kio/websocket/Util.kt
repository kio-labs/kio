/*
 * Copyright 2026, the KWebSocket project contributors
 * SPDX-License-Identifier: Zlib
 */

/*
 * Copyright 2017-2023 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.andannn.kio.websocket

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.InternalIoApi
import kotlinx.io.Source

internal expect fun sha1(bytes: ByteArray): ByteArray

internal class InvalidUtf8Exception(message: String) : IllegalStateException(message)

@OptIn(InternalIoApi::class)
internal fun Source.readCodePointValueOrThrow(): Int {
    if (this is Buffer) {
        return commonReadUtf8CodePointOrThrow()
    }
    require(1)

    val b0 = buffer[0].toInt()
    when {
        b0 and 0xe0 == 0xc0 -> require(2)
        b0 and 0xf0 == 0xe0 -> require(3)
        b0 and 0xf8 == 0xf0 -> require(4)
    }

    return buffer.commonReadUtf8CodePointOrThrow()
}

private fun Buffer.commonReadUtf8CodePointOrThrow(): Int {
    require(1)

    val b0 = this[0]
    var codePoint: Int
    val byteCount: Int
    val min: Int

    when {
        b0 and 0x80 == 0 -> {
            // 0xxxxxxx.
            codePoint = b0 and 0x7f
            byteCount = 1 // 7 bits (ASCII).
            min = 0x0
        }

        b0 and 0xe0 == 0xc0 -> {
            // 0x110xxxxx
            codePoint = b0 and 0x1f
            byteCount = 2 // 11 bits (5 + 6).
            min = 0x80
        }

        b0 and 0xf0 == 0xe0 -> {
            // 0x1110xxxx
            codePoint = b0 and 0x0f
            byteCount = 3 // 16 bits (4 + 6 + 6).
            min = 0x800
        }

        b0 and 0xf8 == 0xf0 -> {
            // 0x11110xxx
            codePoint = b0 and 0x07
            byteCount = 4 // 21 bits (3 + 6 + 6 + 6).
            min = 0x10000
        }

        else -> {
            // We expected the first byte of a code point but got something else.
            skip(1)
            throw InvalidUtf8Exception("invalid utf8")
        }
    }

    if (size < byteCount) {
        throw EOFException("size < $byteCount: $size (to read code point prefixed 0x${b0.toHexString()})")
    }

    // Read the continuation bytes. If we encounter a non-continuation byte, the sequence consumed
    // thus far is truncated and is decoded as the replacement character. That non-continuation byte
    // is left in the stream for processing by the next call to readUtf8CodePoint().
    for (i in 1 until byteCount) {
        val b = this[i.toLong()]
        if (b and 0xc0 == 0x80) {
            // 0x10xxxxxx
            codePoint = codePoint shl 6
            codePoint = codePoint or (b and 0x3f)
        } else {
            skip(i.toLong())
            throw InvalidUtf8Exception("invalid utf8")
        }
    }

    skip(byteCount.toLong())

    return when {
        codePoint > 0x10ffff -> {
            throw InvalidUtf8Exception("Reject code points larger than the Unicode maximum.")
        }

        codePoint in 0xd800..0xdfff -> {
            throw InvalidUtf8Exception("Reject partial surrogates.")
        }

        codePoint < min -> {
            throw InvalidUtf8Exception("Reject overlong code points.")
        }

        else -> codePoint
    }
}

private inline infix fun Byte.and(other: Int): Int = toInt() and other
