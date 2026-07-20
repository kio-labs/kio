package kio.http.internal.http2

import kio.async.AsyncSink
import kio.http.trace
import kio.http.internal.http2.Http2.FLAG_ACK
import kio.http.internal.http2.Http2.FLAG_END_STREAM
import kio.http.internal.http2.Http2.FLAG_NONE
import kio.http.internal.http2.Http2.TYPE_DATA
import kio.http.internal.http2.Http2.TYPE_GOAWAY
import kio.http.internal.http2.Http2.TYPE_PING
import kio.http.internal.http2.Http2.TYPE_RST_STREAM
import kio.http.internal.http2.Http2.TYPE_SETTINGS
import kio.http.internal.http2.Http2.TYPE_WINDOW_UPDATE
import kio.http.internal.http2.Http2.frameLog
import kotlinx.io.Buffer

context(conn: Http2Connection)
internal suspend fun AsyncSink.writeHeaders(
    outFinished: Boolean,
    streamId: Int,
    headerBlock: List<Header>,
) {
    val hpackBuffer = Buffer()
    conn.hpackWriter.writeHeaders(headerBlock, hpackBuffer)

    val byteCount = hpackBuffer.size
    val length = minOf(conn.maxFrameSize.toLong(), byteCount)
    var flags = if (byteCount == length) Http2.FLAG_END_HEADERS else 0
    if (outFinished) flags = flags or Http2.FLAG_END_STREAM
    conn.writeFrameNonCancellable {
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

context(conn: Http2Connection)
private suspend fun AsyncSink.writeContinuationFrames(
    streamId: Int,
    byteCount: Long,
    hpackBuffer: Buffer,
) {
    var byteCount = byteCount
    while (byteCount > 0L) {
        val length = minOf(conn.maxFrameSize.toLong(), byteCount)
        byteCount -= length

        conn.writeFrameNonCancellable {
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

context(conn: Http2Connection)
internal suspend fun AsyncSink.writeWindowUpdate(
    streamId: Int,
    windowSizeIncrement: Long,
) = conn.writeFrameNonCancellable {
    frameHeader(
        streamId = streamId,
        length = 4,
        type = TYPE_WINDOW_UPDATE,
        flags = FLAG_NONE,
    )
    writeInt(windowSizeIncrement.toInt())
}

context(conn: Http2Connection)
internal suspend fun AsyncSink.writeSetting(
    settings: Settings
) = conn.writeFrameNonCancellable {
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

context(conn: Http2Connection)
internal suspend fun AsyncSink.writePing(
    ack: Boolean,
    payload1: Int,
    payload2: Int,
) = conn.writeFrameNonCancellable {
    frameHeader(
        streamId = 0,
        length = 8,
        type = TYPE_PING,
        flags = if (ack) FLAG_ACK else FLAG_NONE,
    )
    writeInt(payload1)
    writeInt(payload2)
}

context(conn: Http2Connection)
internal suspend fun AsyncSink.writeRstStream(
    streamId: Int,
    errorCode: ErrorCode
) = conn.writeFrameNonCancellable {
    frameHeader(
        streamId = streamId,
        length = 4,
        type = TYPE_RST_STREAM,
        flags = FLAG_NONE,
    )
    writeInt(errorCode.httpCode)
}

/**
 * Tell the peer to stop creating streams and that we last processed `lastGoodStreamId`, or zero
 * if no streams were processed.
 *
 * @param lastGoodStreamId the last stream ID processed, or zero if no streams were processed.
 * @param errorCode reason for closing the connection.
 * @param debugData only valid for HTTP/2; opaque debug data to send.
 */
context(conn: Http2Connection)
internal suspend fun AsyncSink.writeGoAway(
    lastGoodStreamId: Int,
    errorCode: ErrorCode,
    debugData: ByteArray,
) = conn.writeFrameNonCancellable {
    frameHeader(
        streamId = 0,
        length = 8 + debugData.size,
        type = TYPE_GOAWAY,
        flags = FLAG_NONE,
    )
    writeInt(lastGoodStreamId)
    writeInt(errorCode.httpCode)
    if (debugData.isNotEmpty()) {
        write(debugData)
    }
}

context(conn: Http2Connection)
internal suspend fun AsyncSink.frameHeader(
    streamId: Int,
    length: Int,
    type: Int,
    flags: Int,
) {
    if (type != Http2.TYPE_WINDOW_UPDATE) {
        conn.h2Logger.trace(frameLog(false, streamId, length, type, flags))
    }
    val maxFrameSize = conn.maxFrameSize
    require(length <= maxFrameSize) { "FRAME_SIZE_ERROR length > $maxFrameSize: $length" }
    require(streamId and 0x80000000.toInt() == 0) { "reserved bit set: $streamId" }
    writeMedium(length)
    writeByte((type and 0xff).toByte())
    writeByte((flags and 0xff).toByte())
    writeInt(streamId and 0x7fffffff)
}

context(conn: Http2Connection, streamWindowSize: WindowSizeCounter)
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

    var remain = byteCount
    while (remain > 0L) {
        val toWrite = waitUntilCanWrite(remain)

        streamWindowSize.onWrite(toWrite)
        conn.windowSizeCounter.onWrite(toWrite)

        remain -= toWrite.toLong()
        data(outFinished && remain == 0L, streamId, buffer, toWrite)
    }
}

context(conn: Http2Connection, streamWindowSize: WindowSizeCounter)
private suspend fun waitUntilCanWrite(remainByteCount: Long): Int {
    val ret: Int
    while (true) {
        if (calculateWriteSize(remainByteCount) == 0) {
            conn.awaitWindowUpdateEvent()
        }

        val writeSize = calculateWriteSize(remainByteCount)
        if (writeSize > 0) {
            ret = writeSize
            break
        }
    }

    return ret
}

context(conn: Http2Connection, streamWindowSize: WindowSizeCounter)
private fun calculateWriteSize(remainByteCount: Long): Int {
    val toWrite: Int = minOf(conn.maxFrameSize, remainByteCount.toInt())
    val remainWindowSize = minOf(conn.windowSizeCounter.remainWindowSize, streamWindowSize.remainWindowSize)
    return minOf(toWrite, remainWindowSize)
}

context(_: Http2Connection)
private suspend fun AsyncSink.data(
    outFinished: Boolean,
    streamId: Int,
    source: Buffer?,
    byteCount: Int,
) {
    var flags = FLAG_NONE
    if (outFinished) flags = flags or FLAG_END_STREAM
    dataFrame(streamId, flags, source, byteCount)
}

context(conn: Http2Connection)
private suspend fun AsyncSink.dataFrame(
    streamId: Int,
    flags: Int,
    buffer: Buffer?,
    byteCount: Int,
)= conn.writeFrameNonCancellable {
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
