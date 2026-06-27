package kio.async

actual fun openPipe(): Pair<AsyncSource, AsyncSink> {
    val pipe = java.nio.channels.Pipe.open()

    val sourceChannel = pipe.source()
    val sinkChannel = pipe.sink()

    sourceChannel.configureBlocking(false)
    sinkChannel.configureBlocking(false)

    return asyncChannelRawSource(sourceChannel, sourceChannel).buffered() to
            asyncChannelRawSink(sinkChannel, sinkChannel).buffered()
}