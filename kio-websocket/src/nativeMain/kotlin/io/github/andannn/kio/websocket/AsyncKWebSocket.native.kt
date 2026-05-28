/*
 * Copyright 2026, the KWebSocket project contributors
 * SPDX-License-Identifier: Zlib
 */
package io.github.andannn.kio.websocket

import kio.async.asyncFdRawSink
import kio.async.asyncFdRawSource
import kio.async.buffered
import platform.posix.SHUT_WR

fun asyncKioWebSocket(fd: Int, isClient: Boolean): AsyncKioWebSocket = object : InternalWebSocket(
    isClient,
    asyncFdRawSink(fd).buffered(),
    asyncFdRawSource(fd).buffered(),
) {
    override suspend fun close() {
        try {
            sendCloseEventIfNeeded()
        } catch (t: Throwable) {
            // ignore exception because in close
            println("exception when sendCloseEventIfNeeded $t")
        }

        platform.posix.shutdown(fd, SHUT_WR)

        try {
            drainSourceBuffer()
        } catch (t: Throwable) {
            // ignore exception because we are closing
            println("exception when drainSourceBuffer: $t")
        } finally {
            platform.posix.close(fd)
        }
    }
}