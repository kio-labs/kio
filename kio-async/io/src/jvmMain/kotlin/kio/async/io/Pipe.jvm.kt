package kio.async.io

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.SelectionKeyWrapper
import kio.async.poller
import kotlinx.coroutines.currentCoroutineContext

actual suspend fun openPipe(): AsyncRawConnection {
    val poller = currentCoroutineContext().poller
    val pipe = java.nio.channels.Pipe.open()

    val sourceChannel = pipe.source()
    val sinkChannel = pipe.sink()
    sourceChannel.configureBlocking(false)
    sinkChannel.configureBlocking(false)

    val readHandle = SelectionKeyWrapper(sourceChannel)
    val writeHandle = SelectionKeyWrapper(sinkChannel)
    poller.attach(readHandle, POLL_INTEREST_READ)
    poller.attach(writeHandle, POLL_INTEREST_WRITE)

    return object : AsyncRawConnection {
        override val source: AsyncRawSource =
            asyncChannelRawSource(sourceChannel, sourceChannel)
        override val sink: AsyncRawSink =
            asyncChannelRawSink(sinkChannel, sinkChannel)

        override suspend fun close() {
            poller.detach(readHandle, POLL_INTEREST_READ)
            poller.detach(writeHandle, POLL_INTEREST_WRITE)
            sourceChannel.close()
            sinkChannel.close()
        }
    }
}