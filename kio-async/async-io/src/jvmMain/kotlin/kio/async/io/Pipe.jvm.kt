package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SelectionKeyWrapper
import kio.async.attachKey
import kio.async.detachKey
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext

actual suspend fun openPipe(): AsyncRawConnection {
    val io = currentCoroutineContext().poller.io
    val pipe = java.nio.channels.Pipe.open()

    val sourceChannel = pipe.source()
    val sinkChannel = pipe.sink()
    sourceChannel.configureBlocking(false)
    sinkChannel.configureBlocking(false)

    val readHandle = SelectionKeyWrapper(sourceChannel)
    val writeHandle = SelectionKeyWrapper(sinkChannel)
    io.attachKey(readHandle, POLL_INTEREST_READ)
    io.attachKey(writeHandle, POLL_INTEREST_WRITE)

    return object : AsyncRawConnection {
        override val source: AsyncRawSource =
            asyncChannelRawSource(sourceChannel, sourceChannel,io)
        override val sink: AsyncRawSink =
            asyncChannelRawSink(sinkChannel, sinkChannel, io)

        override suspend fun close() {
            io.detachKey(readHandle, POLL_INTEREST_READ)
            io.detachKey(writeHandle, POLL_INTEREST_WRITE)
            sourceChannel.close()
            sinkChannel.close()
        }
    }
}