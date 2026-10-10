package kio.compression

import kio.async.PollerFactory
import kio.async.SuspendIo
import kio.async.close
import kio.async.open
import kio.async.poller
import kio.async.poller.kqueue.Kqueue
import kio.async.readString
import kio.async.runPollEventLoop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.IOException
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.strerror
import kotlin.test.Test
import kotlin.test.assertEquals

class KqueueZipTest : ZipTest() {
    override val pollerFactory: PollerFactory = Kqueue

    @Test
    fun openZipTest(): Unit = runPollEventLoop(pollerFactory) {
        val io = currentCoroutineContext().poller.io
        val fd = openFile(io, "test.zip")
        val entries = io.readZipEntries(fd)
        assertEquals("/hello.txt", entries.first().canonicalPath)

        val entryContent = entries.first().zipEntrySource(io, fd).readString()
        assertEquals("Hello worl", entryContent)
        io.close(fd)
    }

    @Test
    fun openZip64Test(): Unit = runPollEventLoop(pollerFactory) {
        val io = currentCoroutineContext().poller.io
        val fd = openFile(io, "test-zip64.zip")
        val entries = io.readZipEntries(fd)
        assertEquals(1, entries.size)
        assertEquals("/hello.txt", entries.first().canonicalPath)

        val entryContent = entries.first().zipEntrySource(io, fd).readString()
        assertEquals("Hello ZIP64!", entryContent)
        io.close(fd)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun openFile(io: SuspendIo, path: String): Int {
        val fd = io.open(path, O_RDONLY or O_CLOEXEC, 0U)
        if (fd < 0) {
            throw IOException("open failed for $path: ${strerror(-fd)?.toKString()}")
        }

        return fd
    }
}