package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.IoPoller
import kio.async.SelectionKeyWrapper
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext
import java.nio.channels.SelectionKey

suspend fun pipe(): AsyncRawConnection {
    val io = currentCoroutineContext().poller.io
    check(io is IoPoller)
    val pipe = java.nio.channels.Pipe.open()

    val sourceChannel = pipe.source()
    val sinkChannel = pipe.sink()
    sourceChannel.configureBlocking(false)
    sinkChannel.configureBlocking(false)

    val readHandle = SelectionKeyWrapper(sourceChannel)
    val writeHandle = SelectionKeyWrapper(sinkChannel)
    io.attach(readHandle, SelectionKey.OP_READ)
    io.attach(writeHandle, SelectionKey.OP_WRITE)

    return object : AsyncRawConnection {
        override val source: AsyncRawSource =
            asyncChannelRawSource(sourceChannel, sourceChannel,io)
        override val sink: AsyncRawSink =
            asyncChannelRawSink(sinkChannel, sinkChannel, io)

        override suspend fun close() {
            io.detach(readHandle, SelectionKey.OP_READ)
            io.detach(writeHandle, SelectionKey.OP_WRITE)
            sourceChannel.close()
            sinkChannel.close()
        }
    }
}