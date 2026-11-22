package kio.compression

import kio.async.PollerFactory
import kio.async.SuspendIo
import kio.async.poller
import kio.async.poller.poll.PosixPoll
import kio.async.posix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.IOException
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.strerror

class PosixPollZipTest : ZipTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}

internal actual suspend fun withFile(path: String, block: suspend (FileHandle) -> Unit) {
    val io = currentCoroutineContext().poller.io
    val fd = openFile(io, path)

    block(fd)

    io.posix.close(fd)
}


@OptIn(ExperimentalForeignApi::class)
private suspend fun openFile(io: SuspendIo, path: String): Int {
    val fd = io.posix.open(path, O_RDONLY or O_CLOEXEC, 0U)
    if (fd < 0) {
        throw IOException("open failed for $path: ${strerror(-fd)?.toKString()}")
    }

    return fd
}