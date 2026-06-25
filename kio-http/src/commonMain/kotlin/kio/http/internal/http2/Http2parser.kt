/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kio.http.internal.http2

import kio.async.AsyncRawSource
import kio.async.AsyncSource
import kio.async.readByteArray
import kio.async.readByteString
import kio.http.internal.http2.Http2.FLAG_ACK
import kio.http.internal.http2.Http2.frameLog
import kio.http.internal.http2.Http2.frameLogWindowUpdate
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.readByteString

internal suspend fun AsyncSource.readPreface() {
    val connectionPreface = ByteString(readByteArray(Http2.CONNECTION_PREFACE.size))
    println("<< CONNECTION $connectionPreface")
    if (connectionPreface != Http2.CONNECTION_PREFACE) {
        throw IOException("Expected a connection header but was ${connectionPreface.decodeToString()}")
    }
}

sealed interface Frame {
    class Headers(
        val streamId: Int,
        val inFinished: Boolean,
        val headerBlock: List<Header>,
    ) : Frame

    class Data(
        val inFinished: Boolean,
        val streamId: Int,
        val length: Long,
    ): Frame

    class GoAway(
        val lastGoodStreamId: Int,
        val errorCode: ErrorCode,
        val debugData: ByteString,
    ) : Frame

    class Setting(val settings: Settings) : Frame

    data object SettingsAck : Frame

    class WindowUpdate(
        val streamId: Int,
        val windowSizeIncrement: Long,
    ): Frame

    class PingAck(
        val payload1: Int,
        val payload2: Int,
    ): Frame

    class Ping(
        val payload1: Int,
        val payload2: Int,
    ): Frame
}

context(_: Hpack.Reader, _: ContinuationSource)
internal suspend fun AsyncSource.nextFrame(): Frame {
    // Frame header size.
    require(9)

    //  0                   1                   2                   3
    //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |                 Length (24)                   |
    // +---------------+---------------+---------------+
    // |   Type (8)    |   Flags (8)   |
    // +-+-+-----------+---------------+-------------------------------+
    // |R|                 Stream Identifier (31)                      |
    // +=+=============================================================+
    // |                   Frame Payload (0...)                      ...
    // +---------------------------------------------------------------+

    val length = readMedium()
    if (length > Http2.INITIAL_MAX_FRAME_SIZE) {
        throw IOException("FRAME_SIZE_ERROR: $length")
    }

    val type = readByte() and 0xff
    val flags = readByte() and 0xff
    val streamId = readInt() and 0x7fffffff // Ignore reserved bit.

    if (type != Http2.TYPE_WINDOW_UPDATE) {
        println(frameLog(true, streamId, length, type, flags))
    }

    return when (type) {
        Http2.TYPE_DATA -> readData(length, flags, streamId)
        Http2.TYPE_HEADERS -> readHeaders(length, flags, streamId)
        Http2.TYPE_PRIORITY -> TODO("TYPE_PRIORITY")
        Http2.TYPE_RST_STREAM -> TODO("TYPE_RST_STREAM")
        Http2.TYPE_SETTINGS -> readSettings(length, flags, streamId)
        Http2.TYPE_PUSH_PROMISE -> TODO("TYPE_PUSH_PROMISE")
        Http2.TYPE_PING -> readPing(length, flags, streamId)
        Http2.TYPE_GOAWAY -> readGoAway(length, flags, streamId)
        Http2.TYPE_WINDOW_UPDATE -> readWindowUpdate(length, flags, streamId)
        else -> TODO()
    }
}

context(_: Hpack.Reader)
internal suspend fun AsyncSource.readSettings(
    length: Int,
    flags: Int,
    streamId: Int,
): Frame {
    if (streamId != 0) throw IOException("TYPE_SETTINGS streamId != 0")
    if (flags and Http2.FLAG_ACK != 0) {
        if (length != 0) throw IOException("FRAME_SIZE_ERROR ack frame should be empty!")
        return Frame.SettingsAck
    }

    if (length % 6 != 0) throw IOException("TYPE_SETTINGS length % 6 != 0: $length")
    val settings = Settings()
    for (i in 0 until length step 6) {
        val id = readShort() and 0xffff
        val value = readInt()

        when (id) {
            // SETTINGS_HEADER_TABLE_SIZE
            1 -> {
            }

            // SETTINGS_ENABLE_PUSH
            2 -> {
                if (value != 0 && value != 1) {
                    throw IOException("PROTOCOL_ERROR SETTINGS_ENABLE_PUSH != 0 or 1")
                }
            }

            // SETTINGS_MAX_CONCURRENT_STREAMS
            3 -> {
            }

            // SETTINGS_INITIAL_WINDOW_SIZE
            4 -> {
                if (value < 0) {
                    throw IOException("PROTOCOL_ERROR SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1")
                }
            }

            // SETTINGS_MAX_FRAME_SIZE
            5 -> {
                if (value < Http2.INITIAL_MAX_FRAME_SIZE || value > 16777215) {
                    throw IOException("PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: $value")
                }
            }

            // SETTINGS_MAX_HEADER_LIST_SIZE
            6 -> { // Advisory only, so ignored.
            }

            // Must ignore setting with unknown id.
            else -> {
            }
        }
        settings[id] = value
    }

    return Frame.Setting(settings)
}

private suspend fun AsyncSource.readPing(
    length: Int,
    flags: Int,
    streamId: Int,
): Frame {
    if (length != 8) throw IOException("TYPE_PING length != 8: $length")
    if (streamId != 0) throw IOException("TYPE_PING streamId != 0")
    val payload1 = readInt()
    val payload2 = readInt()
    val ack = flags and FLAG_ACK != 0
    if (ack) return Frame.PingAck(payload1, payload2)
    return Frame.Ping(payload1, payload2)
}

private suspend fun AsyncSource.readGoAway(
    length: Int,
    flags: Int,
    streamId: Int,
): Frame.GoAway {
    if (length < 8) throw IOException("TYPE_GOAWAY length < 8: $length")
    if (streamId != 0) throw IOException("TYPE_GOAWAY streamId != 0")
    val lastStreamId = readInt()
    val errorCodeInt = readInt()
    val opaqueDataLength = length - 8
    val errorCode =
        ErrorCode.fromHttp2(errorCodeInt) ?: throw IOException(
            "TYPE_GOAWAY unexpected error code: $errorCodeInt",
        )
    var debugData = ByteString()
    if (opaqueDataLength > 0) { // Must read debug data in order to not corrupt the connection.
        debugData = readByteString(opaqueDataLength)
    }
    return Frame.GoAway(lastStreamId, errorCode, debugData)
}

private suspend fun AsyncSource.readWindowUpdate(
    length: Int,
    flags: Int,
    streamId: Int,
): Frame.WindowUpdate {
    val increment: Long
    try {
        if (length != 4) throw IOException("TYPE_WINDOW_UPDATE length !=4: $length")
        increment = readInt() and 0x7fffffffL
        if (increment == 0L) throw IOException("windowSizeIncrement was 0")
    } catch (e: Exception) {
        println(frameLog(true, streamId, length, Http2.TYPE_WINDOW_UPDATE, flags))
        throw e
    }
    println(
        frameLogWindowUpdate(
            inbound = true,
            streamId = streamId,
            length = length,
            windowSizeIncrement = increment,
        )
    )
    return Frame.WindowUpdate(streamId, increment)
}

context(_: Hpack.Reader, _: ContinuationSource)
private suspend fun AsyncSource.readHeaders(
    length: Int,
    flags: Int,
    streamId: Int,
): Frame.Headers {
    if (streamId == 0) throw IOException("PROTOCOL_ERROR: TYPE_HEADERS streamId == 0")

    val endStream = (flags and Http2.FLAG_END_STREAM) != 0
    val padding = if (flags and Http2.FLAG_PADDED != 0) readByte() and 0xff else 0

    var headerBlockLength = length
    if (flags and Http2.FLAG_PRIORITY != 0) {
        readPriority(streamId)
        headerBlockLength -= 5 // account for above read.
    }
    headerBlockLength = lengthWithoutPadding(headerBlockLength, flags, padding)
    val headerBlock = readHeaderBlock(headerBlockLength, padding, flags, streamId)

    return Frame.Headers(streamId, endStream, headerBlock)
}

context(hpackReader: Hpack.Reader, continuation: ContinuationSource)
private suspend fun readHeaderBlock(
    length: Int,
    padding: Int,
    flags: Int,
    streamId: Int,
): List<Header> {
    continuation.left = length
    continuation.padding = padding
    continuation.flags = flags
    continuation.streamId = streamId
//
//    // TODO: Concat multi-value headers with 0x0, except COOKIE, which uses 0x3B, 0x20.
//    // http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-8.1.2.5
    hpackReader.readHeaders()
    return hpackReader.getAndResetHeaderList()
}

private suspend fun AsyncSource.readPriority(
//    handler: Handler,
    streamId: Int,
) {
    val w1 = readInt()
    val exclusive = w1 and 0x80000000.toInt() != 0
    val streamDependency = w1 and 0x7fffffff
    val weight = (readByte() and 0xff) + 1
//    handler.priority(streamId, streamDependency, weight, exclusive)
}

/**
 * Decompression of the header block occurs above the framing layer. This class lazily reads
 * continuation frames as they are needed by [Hpack.Reader.readHeaders].
 */
internal class ContinuationSource(
    private val source: AsyncSource,
) : AsyncRawSource {
    var flags: Int = 0
    var streamId: Int = 0

    var left: Int = 0
    var padding: Int = 0

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        while (left == 0) {
            source.skip(padding.toLong())
            padding = 0
            if (flags and Http2.FLAG_END_HEADERS != 0) return -1L
            readContinuationHeader()
            // TODO: test case for empty continuation header?
        }

        val read = source.readAtMostTo(sink, minOf(byteCount, left.toLong()))
        if (read == -1L) return -1L
        left -= read.toInt()
        return read
    }

    private suspend fun readContinuationHeader() {
        val previousStreamId = streamId

        val length = source.readMedium()
        left = length
        val type = source.readByte() and 0xff
        flags = source.readByte() and 0xff
        println(frameLog(true, streamId, length, type, flags))
        streamId = source.readInt() and 0x7fffffff
        if (type != Http2.TYPE_CONTINUATION) throw IOException("$type != TYPE_CONTINUATION")
        if (streamId != previousStreamId) throw IOException("TYPE_CONTINUATION streamId changed")
    }

    override suspend fun close() = Unit
}

private suspend fun AsyncSource.readData(
    length: Int,
    flags: Int,
    streamId: Int,
): Frame.Data {
    if (streamId == 0) throw IOException("PROTOCOL_ERROR: TYPE_DATA streamId == 0")

    // TODO: checkState open or half-closed (local) or raise STREAM_CLOSED
    val inFinished = flags and Http2.FLAG_END_STREAM != 0
    val gzipped = flags and Http2.FLAG_COMPRESSED != 0
    if (gzipped) {
        throw IOException("PROTOCOL_ERROR: FLAG_COMPRESSED without SETTINGS_COMPRESS_DATA")
    }

    val padding = if (flags and Http2.FLAG_PADDED != 0) readByte() and 0xff else 0
    val dataLength = lengthWithoutPadding(length, flags, padding)
    skip(padding.toLong())
    return Frame.Data(inFinished, streamId, dataLength.toLong())
}

private fun lengthWithoutPadding(
    length: Int,
    flags: Int,
    padding: Int,
): Int {
    var result = length
    if (flags and Http2.FLAG_PADDED != 0) result-- // Account for reading the padding length.
    if (padding > result) {
        throw IOException("PROTOCOL_ERROR padding $padding > remaining length $result")
    }
    result -= padding
    return result
}
