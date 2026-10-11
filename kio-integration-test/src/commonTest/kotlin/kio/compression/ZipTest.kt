package kio.compression

import kio.async.PollerFactory
import kio.async.inMemoryAsyncBuffer
import kio.async.writeString
import kio.async.poller
import kio.async.readString
import kio.async.runPollEventLoop
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class ZipTest {
    abstract val pollerFactory: PollerFactory

    @Test
    fun writeZipTest(): Unit = runPollEventLoop(pollerFactory) {
        val io = currentCoroutineContext().poller.io
        val content = "Hello ZIP! 写入测试。".repeat(1000)
        val archive = Buffer()
        archive.inMemoryAsyncBuffer().writeZip {
            val source = Buffer().inMemoryAsyncBuffer()
            source.writeString(content)
            file("hello.txt", source)
        }

        val path = Path("write-zip-${Random.nextLong().toULong()}.zip")
        try {
            SystemFileSystem.sink(path).buffered().use { sink ->
                sink.write(archive, archive.size)
            }
            withFile(path.toString()) { fd ->
                val entries = io.readZipEntries(fd)
                assertEquals(1, entries.size)
                assertEquals("/hello.txt", entries.single().canonicalPath)
                assertEquals(content, entries.single().zipEntrySource(io, fd).readString())
            }
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun openZipTest(): Unit = runPollEventLoop(pollerFactory) {
        val io = currentCoroutineContext().poller.io

        withFile("test.zip") { fd ->
            val entries = io.readZipEntries(fd)
            assertEquals("/hello.txt", entries.first().canonicalPath)

            val entryContent = entries.first().zipEntrySource(io, fd).readString()
            assertEquals("Hello world", entryContent)
        }
    }

    @Test
    fun openZip64Test(): Unit = runPollEventLoop(pollerFactory) {
        val io = currentCoroutineContext().poller.io

        withFile("test-zip64.zip") { fd ->
            val entries = io.readZipEntries(fd)
            assertEquals(1, entries.size)
            assertEquals("/hello.txt", entries.first().canonicalPath)

            val entryContent = entries.first().zipEntrySource(io, fd).readString()
            assertEquals("Hello ZIP64!", entryContent)
        }
    }
}

internal expect suspend fun withFile(path: String, block: suspend (FileHandle) -> Unit)
