package kio.async.io

import kotlinx.cinterop.*
import kotlinx.io.IOException
import platform.posix.CLD_DUMPED
import platform.posix.CLD_EXITED
import platform.posix.CLD_KILLED
import platform.posix.pid_tVar
import platform.posix.siginfo_t

expect val siginfo_t.pid: Int

val siginfo_t.isExited: Boolean
    get() = si_code == CLD_EXITED

val siginfo_t.isSignaled: Boolean
    get() = si_code == CLD_KILLED || si_code == CLD_DUMPED

expect val siginfo_t.exitStatus: Int

@OptIn(ExperimentalForeignApi::class)
class PosixSpawnScope internal constructor(
    internal val fileActions: CValuesRef<*>
) {
    var argv0: String? = null
    internal var envMap: MutableMap<String, String>? = null

    fun env(vararg pairs: Pair<String, String>) {
        val map = envMap ?: mutableMapOf<String, String>().also { envMap = it }
        pairs.forEach { (k, v) -> map[k] = v }
    }

    fun env(map: Map<String, String>) {
        val target = envMap ?: mutableMapOf<String, String>().also { envMap = it }
        target.putAll(map)
    }

    fun dup2(oldFd: Int, newFd: Int): Int {
        return fileActionsAddDup2(fileActions, oldFd, newFd)
    }

    fun close(fd: Int): Int {
        return fileActionsAddClose(fileActions, fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun posixSpawn(
    file: String,
    args: List<String> = emptyList(),
    configure: (PosixSpawnScope.() -> Unit)? = null,
): Int = memScoped {
    val scope = if (configure != null) {
        PosixSpawnScope(allocFileActions(this)).apply(configure)
    } else null

    val pid = alloc<pid_tVar>()

    val arguments = ArrayList<String>(args.size + 1).apply {
        add(scope?.argv0 ?: file)
        addAll(args)
    }
    val argv = allocCStringArray(arguments)

    val envp = scope?.envMap?.let { map ->
        allocCStringArray(map.map { (k, v) -> "$k=$v" })
    } ?: environ

    try {
        val result = posix_spawnp(pid.ptr, file, scope?.fileActions, null, argv, envp)
        if (result != 0) {
            throw IOException("posix_spawnp failed for '$file': ${resultErrorMessage(result)}")
        }
        pid.value
    } finally {
        scope?.let { fileActionsDestroy(it.fileActions) }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun AutofreeScope.allocCStringArray(strings: List<String>): CPointer<CPointerVar<ByteVar>> {
    val array = allocArray<CPointerVar<ByteVar>>(strings.size + 1)
    strings.forEachIndexed { index, str ->
        array[index] = str.cstr.getPointer(this)
    }
    array[strings.size] = null
    return array
}

@OptIn(ExperimentalForeignApi::class)
internal expect fun allocFileActions(scope: AutofreeScope): CValuesRef<*>

@OptIn(ExperimentalForeignApi::class)
internal expect fun fileActionsAddDup2(fileActions: CValuesRef<*>, oldFd: Int, newFd: Int): Int

@OptIn(ExperimentalForeignApi::class)
internal expect fun fileActionsAddClose(fileActions: CValuesRef<*>, fd: Int): Int

@OptIn(ExperimentalForeignApi::class)
internal expect fun fileActionsDestroy(fileActions: CValuesRef<*>)

@OptIn(ExperimentalForeignApi::class)
internal expect val environ: CPointer<CPointerVar<ByteVar>>?

@OptIn(ExperimentalForeignApi::class)
internal expect fun posix_spawnp(
    pid: CValuesRef<pid_tVar>?,
    file: String?,
    fileActions: CValuesRef<*>?,
    attributes: Nothing?,
    argv: CValuesRef<CPointerVar<ByteVar>>?,
    envp: CValuesRef<CPointerVar<ByteVar>>?,
): Int
