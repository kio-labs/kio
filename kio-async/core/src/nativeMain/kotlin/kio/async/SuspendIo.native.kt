@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVarOf
import platform.posix.sockaddr

actual interface SuspendIo {
    fun attach(fd: Int, event: PollInterest) {}
    fun detach(fd: Int, event: PollInterest) {}

    suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long
    suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long
    suspend fun suspendAccept(fd: Int, addr: CValuesRef<sockaddr>, addrLen: CValuesRef<UIntVarOf<UInt>>): Int
    suspend fun suspendConnect(fd: Int, addr: CValuesRef<sockaddr>?, len: UInt)
}
