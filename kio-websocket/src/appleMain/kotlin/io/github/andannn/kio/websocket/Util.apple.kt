/*
 * Copyright 2026, the KWebSocket project contributors
 * SPDX-License-Identifier: Zlib
 */
package io.github.andannn.kio.websocket

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.posix.errno
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual fun sha1(bytes: ByteArray): ByteArray {
    val result = ByteArray(CC_SHA1_DIGEST_LENGTH)

    bytes.usePinned { input ->
        result.usePinned { output ->
            CC_SHA1(
                input.addressOf(0),
                bytes.size.convert(),
                output.addressOf(0).reinterpret()
            )
        }
    }

    return result
}