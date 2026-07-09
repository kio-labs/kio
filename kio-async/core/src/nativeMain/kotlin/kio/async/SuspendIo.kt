@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.read
import platform.posix.write

interface SuspendIo {
    suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long
    suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long
}

private class SuspendSyscallIo(
    private val poller: AttachablePoller
): SuspendIo {
    override suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long {
        poller.awaitIo(fd, POLL_INTEREST_WRITE)
        return write(fd, buf, byte)
    }

    override suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long {
        poller.awaitIo(fd, POLL_INTEREST_READ)
        return read(fd, bytes, nbyte)
    }
}
