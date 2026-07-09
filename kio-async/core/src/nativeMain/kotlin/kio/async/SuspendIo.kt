@file:OptIn(ExperimentalForeignApi::class)

package kio.async

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi

interface SuspendIo {
    suspend fun suspendWrite(fd: Int, buf: CValuesRef<*>?, byte: ULong): Long
    suspend fun suspendRead(fd: Int, bytes: CValuesRef<*>?, nbyte: ULong): Long
}