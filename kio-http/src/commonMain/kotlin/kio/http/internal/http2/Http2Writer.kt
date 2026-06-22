package kio.http.internal.http2

import kio.async.AsyncSink
import kio.http.internal.http2.Http2.FLAG_ACK
import kio.http.internal.http2.Http2.FLAG_END_STREAM
import kio.http.internal.http2.Http2.FLAG_NONE
import kio.http.internal.http2.Http2.INITIAL_MAX_FRAME_SIZE
import kio.http.internal.http2.Http2.TYPE_DATA
import kio.http.internal.http2.Http2.TYPE_PING
import kio.http.internal.http2.Http2.TYPE_SETTINGS
import kio.http.internal.http2.Http2.frameLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer

// TODO: move this field to Http2Connection
private var maxFrameSize: Int = INITIAL_MAX_FRAME_SIZE

context(writerMutex: Mutex)
internal suspend fun AsyncSink.writeHeaders(
    outFinished: Boolean,
    streamId: Int,
    headerBlock: List<Header>,
    hpackWriter: Hpack.Writer
) {
    val hpackBuffer = Buffer()
    hpackWriter.writeHeaders(headerBlock, hpackBuffer)

    val byteCount = hpackBuffer.size
    val length = minOf(maxFrameSize.toLong(), byteCount)
    var flags = if (byteCount == length) Http2.FLAG_END_HEADERS else 0
    if (outFinished) flags = flags or Http2.FLAG_END_STREAM
    writerMutex.withLock {
        frameHeader(
            streamId = streamId,
            length = length.toInt(),
            type = Http2.TYPE_HEADERS,
            flags = flags,
        )
        write(hpackBuffer, length)
    }

    if (byteCount > length) writeContinuationFrames(streamId, byteCount - length, hpackBuffer)
}

context(writerMutex: Mutex)
private suspend fun AsyncSink.writeContinuationFrames(
    streamId: Int,
    byteCount: Long,
    hpackBuffer: Buffer,
) {
    var byteCount = byteCount
    while (byteCount > 0L) {
        val length = minOf(maxFrameSize.toLong(), byteCount)
        byteCount -= length

        writerMutex.withLock {
            frameHeader(
                streamId = streamId,
                length = length.toInt(),
                type = Http2.TYPE_CONTINUATION,
                flags = if (byteCount == 0L) Http2.FLAG_END_HEADERS else 0,
            )
            write(hpackBuffer, length)
        }
    }
}

context(writerMutex: Mutex)
internal suspend fun AsyncSink.writeSetting(
    settings: Settings
) = writerMutex.withLock {
    frameHeader(
        streamId = 0,
        length = settings.size() * 6,
        type = TYPE_SETTINGS,
        flags = FLAG_NONE,
    )
    for (i in 0 until Settings.COUNT) {
        if (!settings.isSet(i)) continue
        writeShort(i.toShort())
        writeInt(settings[i])
    }
}

context(writerMutex: Mutex)
internal suspend fun AsyncSink.writePing(
    ack: Boolean,
    payload1: Int,
    payload2: Int,
) = writerMutex.withLock {
    frameHeader(
        streamId = 0,
        length = 8,
        type = TYPE_PING,
        flags = if (ack) FLAG_ACK else FLAG_NONE,
    )
    writeInt(payload1)
    writeInt(payload2)
}

internal suspend fun AsyncSink.frameHeader(
    streamId: Int,
    length: Int,
    type: Int,
    flags: Int,
) {
    if (type != Http2.TYPE_WINDOW_UPDATE) {
        println(frameLog(false, streamId, length, type, flags))
    }
    require(length <= maxFrameSize) { "FRAME_SIZE_ERROR length > $maxFrameSize: $length" }
    require(streamId and 0x80000000.toInt() == 0) { "reserved bit set: $streamId" }
    writeMedium(length)
    writeByte((type and 0xff).toByte())
    writeByte((flags and 0xff).toByte())
    writeInt(streamId and 0x7fffffff)
}

context(_: Mutex)
internal suspend fun AsyncSink.writeData(
    streamId: Int,
    outFinished: Boolean,
    buffer: Buffer?,
    byteCount: Long,
) {
    if (byteCount == 0L) {
        data(outFinished, streamId, buffer, 0)
        return
    }

    var byteCount = byteCount
    while (byteCount > 0L) {
        // TODO: await io if no more WINDOW_SIZE
        val toWrite: Int = minOf(INITIAL_MAX_FRAME_SIZE, byteCount.toInt())

        byteCount -= toWrite.toLong()
        data(outFinished && byteCount == 0L, streamId, buffer, toWrite)
    }
}


context(writeMutex: Mutex)
private suspend fun AsyncSink.data(
    outFinished: Boolean,
    streamId: Int,
    source: Buffer?,
    byteCount: Int,
) {
    writeMutex.withLock {
        var flags = FLAG_NONE
        if (outFinished) flags = flags or FLAG_END_STREAM
        dataFrame(streamId, flags, source, byteCount)
    }
}

private suspend fun AsyncSink.dataFrame(
    streamId: Int,
    flags: Int,
    buffer: Buffer?,
    byteCount: Int,
) {
    frameHeader(
        streamId = streamId,
        length = byteCount,
        type = TYPE_DATA,
        flags = flags,
    )
    if (byteCount > 0) {
        write(buffer!!, byteCount.toLong())
    }
}

private suspend fun AsyncSink.writeMedium(medium: Int) {
    writeByte((medium.ushr(16) and 0xff).toByte())
    writeByte((medium.ushr(8) and 0xff).toByte())
    writeByte((medium and 0xff).toByte())
}
