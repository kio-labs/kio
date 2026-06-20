package kio.http.internal.http2

import kio.async.AsyncSink
import kio.http.internal.http2.Http2.FLAG_NONE
import kio.http.internal.http2.Http2.INITIAL_MAX_FRAME_SIZE
import kio.http.internal.http2.Http2.TYPE_SETTINGS
import kio.http.internal.http2.Http2.frameLog
import kotlinx.io.Buffer

// TODO: move this field to Http2Connection
private var maxFrameSize: Int = INITIAL_MAX_FRAME_SIZE

internal suspend fun AsyncSink.writeHeaders(
    outFinished: Boolean,
    streamId: Int,
    headerBlock: List<Header>,
    hpackBuffer: Buffer,
    hpackWriter: Hpack.Writer
) {
    hpackWriter.writeHeaders(headerBlock)

    val byteCount = hpackBuffer.size
    val length = minOf(maxFrameSize.toLong(), byteCount)
    var flags = if (byteCount == length) Http2.FLAG_END_HEADERS else 0
    if (outFinished) flags = flags or Http2.FLAG_END_STREAM
    frameHeader(
        streamId = streamId,
        length = length.toInt(),
        type = Http2.TYPE_HEADERS,
        flags = flags,
    )
    write(hpackBuffer, length)

    if (byteCount > length) writeContinuationFrames(streamId, byteCount - length, hpackBuffer)
}

private suspend fun AsyncSink.writeContinuationFrames(
    streamId: Int,
    byteCount: Long,
    hpackBuffer: Buffer,
) {
    var byteCount = byteCount
    while (byteCount > 0L) {
        val length = minOf(maxFrameSize.toLong(), byteCount)
        byteCount -= length
        frameHeader(
            streamId = streamId,
            length = length.toInt(),
            type = Http2.TYPE_CONTINUATION,
            flags = if (byteCount == 0L) Http2.FLAG_END_HEADERS else 0,
        )
        write(hpackBuffer, length)
    }
}

internal suspend fun AsyncSink.writeSetting(
    settings: Settings
) {
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

private suspend fun AsyncSink.writeMedium(medium: Int) {
    writeByte((medium.ushr(16) and 0xff).toByte())
    writeByte((medium.ushr(8) and 0xff).toByte())
    writeByte((medium and 0xff).toByte())
}
