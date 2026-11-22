@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kio.async.io

import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.pid_tVar

@Suppress("UNCHECKED_CAST")
private inline val CValuesRef<*>.asFileActions: CValuesRef<posix_spawn_file_actions_t>
    get() = this as CValuesRef<posix_spawn_file_actions_t>

internal actual fun allocFileActions(scope: AutofreeScope): CValuesRef<*> {
    val actions = scope.alloc<posix_spawn_file_actions_t>()
    posix_spawn_file_actions_init(actions.ptr)
    return actions.ptr
}

internal actual fun fileActionsAddDup2(fileActions: CValuesRef<*>, oldFd: Int, newFd: Int): Int =
    posix_spawn_file_actions_adddup2(fileActions.asFileActions, oldFd, newFd)

internal actual fun fileActionsAddClose(fileActions: CValuesRef<*>, fd: Int): Int =
    posix_spawn_file_actions_addclose(fileActions.asFileActions, fd)

internal actual fun fileActionsDestroy(fileActions: CValuesRef<*>) {
    posix_spawn_file_actions_destroy(fileActions.asFileActions)
}

internal actual val environ: CPointer<CPointerVar<ByteVar>>?
    get() = platform.posix.__environ

actual val platform.posix.siginfo_t.pid: Int
    get() = _sifields._sigchld.si_pid

actual val platform.posix.siginfo_t.exitStatus: Int
    get() = _sifields._sigchld.si_status

internal actual fun posix_spawnp(
    pid: CValuesRef<pid_tVar>?,
    file: String?,
    fileActions: CValuesRef<*>?,
    attributes: Nothing?,
    argv: CValuesRef<CPointerVar<ByteVar>>?,
    envp: CValuesRef<CPointerVar<ByteVar>>?,
): Int = platform.linux.posix_spawnp(
    pid,
    file,
    fileActions?.asFileActions,
    attributes,
    argv,
    envp
)
