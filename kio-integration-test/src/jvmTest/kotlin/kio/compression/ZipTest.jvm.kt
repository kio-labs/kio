package kio.compression

import kio.async.PollerFactory
import kio.async.poller.select.Select
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

class JvmZipTest : ZipTest() {
    override val pollerFactory: PollerFactory = Select
}

internal actual suspend fun withFile(path: String, block: suspend (FileHandle) -> Unit) {
    FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { channel ->
        block(FileHandle(channel))
    }
}
