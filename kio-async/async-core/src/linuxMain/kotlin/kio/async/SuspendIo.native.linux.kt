@file:OptIn(ExperimentalForeignApi::class)
package kio.async

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import linux.platform.AT_FDCWD
import linux.platform.statx
import platform.linux.SYS_unlinkat
import platform.posix.syscall

private const val SYS_STATX = 332L
private const val SYS_pipe2 = 293L
private const val SYS_pidfd_open = 434L

interface LinuxApi {
    suspend fun pidfdOpen(pid: Int, flags: UInt = 0U): Int = syscall(SYS_pidfd_open, pid, flags).toInt().negErrno()
    suspend fun pipe(fds: CPointer<IntVarOf<Int>>?, pipeFlags: Int): Int = syscall(SYS_pipe2, fds, pipeFlags).toInt().negErrno()
    suspend fun statx(dirfd: Int, path: String?, flags: Int, mask: UInt, buf: CPointer<statx>?): Int = memScoped {
        val cPath = path?.cstr?.getPointer(this)
        syscall(SYS_STATX, dirfd, cPath, flags, mask, buf).toInt().negErrno()
    }
    suspend fun unlinkat(fd: Int, path: String?, flags: Int): Int = memScoped {
        val cPath = path?.cstr?.getPointer(this)
        syscall(SYS_unlinkat.toLong(), fd, cPath, flags).toInt().negErrno()
    }
    suspend fun unlink(path: String?, flags: Int): Int = unlinkat(AT_FDCWD, path, flags)
}

val SuspendIo.linux: LinuxApi
    get() = this as LinuxApi

actual interface SuspendIo: PosixApi, LinuxApi
