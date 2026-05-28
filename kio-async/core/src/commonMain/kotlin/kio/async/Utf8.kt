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

package kio.async

import kotlinx.io.Buffer
import kotlinx.io.DelicateIoApi
import kotlinx.io.InternalIoApi
import kotlinx.io.indexOf
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind.EXACTLY_ONCE
import kotlin.contracts.contract

@OptIn(DelicateIoApi::class)
suspend fun AsyncSink.writeString(string: String, startIndex: Int = 0, endIndex: Int = string.length) {
    checkBounds(string.length, startIndex, endIndex)

    writeToInternalBuffer {
        it.writeString(string, startIndex, endIndex)
    }
}

@OptIn(InternalIoApi::class)
public suspend fun AsyncSource.readString(byteCount: Long): String {
    require(byteCount)
    return buffer.readString(byteCount)
}

public suspend fun AsyncSource.readString(): String {
    request(Long.MAX_VALUE) // Request all data
    return buffer.readString()
}

@DelicateIoApi
@OptIn(InternalIoApi::class, ExperimentalContracts::class)
suspend inline fun AsyncSink.writeToInternalBuffer(lambda: (Buffer) -> Unit) {
    contract {
        callsInPlace(lambda, EXACTLY_ONCE)
    }
    lambda(this.buffer)
    this.hintEmit()
}

@OptIn(InternalIoApi::class)
public suspend fun AsyncSource.readLine(): String? {
    if (!request(1)) return null

    var lfIndex = this.indexOf('\n'.code.toByte())
    return when (lfIndex) {
        -1L -> readString()
        0L -> {
            skip(1)
            ""
        }

        else -> {
            var skipBytes = 1
            if (buffer[lfIndex - 1] == '\r'.code.toByte()) {
                lfIndex -= 1
                skipBytes += 1
            }
            val string = readString(lfIndex)
            skip(skipBytes.toLong())
            string
        }
    }
}

@OptIn(InternalIoApi::class)
public suspend fun AsyncSource.indexOf(byte: Byte, startIndex: Long = 0L, endIndex: Long = Long.MAX_VALUE): Long {
    require(startIndex in 0..endIndex) {
        if (endIndex < 0) {
            "startIndex ($startIndex) and endIndex ($endIndex) should be non negative"
        } else {
            "startIndex ($startIndex) is not within the range [0..endIndex($endIndex))"
        }
    }
    if (startIndex == endIndex) return -1L

    var offset = startIndex
    while (offset < endIndex && request(offset + 1)) {
        val idx = buffer.indexOf(byte, offset, minOf(endIndex, buffer.size))
        if (idx != -1L) {
            return idx
        }
        offset = buffer.size
    }
    return -1L
}
