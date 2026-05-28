package io.github.andannn.kio.websocket

import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.readByteArray
import kio.async.readLine
import kio.async.writeString
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readUShort
import kotlinx.io.writeString
import kotlinx.io.writeUShort
import kotlin.experimental.and
import kotlin.experimental.xor
import kotlin.io.encoding.Base64

class ProtocolException(val closeCode: CloseCode, message: String) : IOException(message)

// https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1
enum class CloseCode(val code: Int) {
    NORMAL(1000),
    GOING_AWAY(1001),
    PROTOCOL_ERROR(1002),
    UNSUPPORTED_DATA(1003),

    ABNORMAL_CLOSURE(1006),

    INVALID_PAYLOAD(1007),
    POLICY_VIOLATION(1008),
    MESSAGE_TOO_BIG(1009),
    MANDATORY_EXTENSION(1010),
    INTERNAL_ERROR(1011),

    SERVICE_RESTART(1012),
    TRY_AGAIN_LATER(1013),
    BAD_GATEWAY(1014),
}

enum class MessageType {
    TEXT,
    BIN,
}

sealed interface WebSocketEvent {
    data class Message(val type: MessageType) : WebSocketEvent
    data object Close : WebSocketEvent
}


interface AsyncKioWebSocket {
    suspend fun close()

    suspend fun serverHandShake(): Result<Unit>

    suspend fun clientHandShake(
        path: String,
        host: String,
        clientSecKey: String = CLIENT_SEC_KEY
    ): Result<Unit>

    suspend fun readMessage(sink: Sink): Result<WebSocketEvent>

    suspend fun sendTextMessage(source: Source, chunkSize: Long = CHUNK_SIZE)

    suspend fun sendBinMessage(bin: Source, chunkSize: Long = CHUNK_SIZE)

    suspend fun sendClose(code: CloseCode, reason: String? = null)
}

suspend fun AsyncKioWebSocket.sendTextMessage(text: String, chunkSize: Long = CHUNK_SIZE) {
    sendMessage(MessageType.TEXT, payload = Buffer().apply { writeString(text) }, chunkSize)
}

internal abstract class InternalWebSocket(
    override val isClient: Boolean,
    val asyncSink: AsyncSink,
    val source: AsyncSource,
) : AsyncKioWebSocket, KWebSocket {
    override var needSendCloseEvent: Boolean = true

    override suspend fun sendClose(code: CloseCode, reason: String?) {
        sendClose(
            code,
            reason,
            sendFrame = { a, b, c, d, e -> asyncSink.sendFrame(a, b, c, d, e) }
        )
    }

    override suspend fun sendBinMessage(bin: Source, chunkSize: Long) {
        sendMessage(MessageType.BIN, payload = bin, chunkSize)
    }

    override suspend fun sendTextMessage(source: Source, chunkSize: Long) {
        sendMessage(MessageType.TEXT, payload = source, chunkSize)
    }

    override suspend fun readMessage(sink: Sink): Result<WebSocketEvent> =
        runCatching {
            readMessage(
                sink = sink,
                isClient = isClient,
                readFrame = { source.readFrame() },
                sendFrame = { a, b, c, d, e -> asyncSink.sendFrame(a, b, c, d, e) }
            )
        }

    override suspend fun clientHandShake(
        path: String,
        host: String,
        clientSecKey: String
    ): Result<Unit> = runCatching {
        clientHandShake(
            path = path,
            host = host,
            clientSecKey = clientSecKey,
            parseHeaders = { source.parseHeaders() },
            writeString = { a, b, c -> asyncSink.writeString(a, b, c) },
            flush = { asyncSink.flush() }
        )
    }

    override suspend fun serverHandShake() = runCatching {
        serverHandShake(
            parseHeaders = { source.parseHeaders() },
            writeString = { a, b, c -> asyncSink.writeString(a, b, c) },
            flush = { asyncSink.flush() }
        )
    }

    suspend fun sendCloseEventIfNeeded() {
        if (needSendCloseEvent) sendClose(CloseCode.NORMAL, "Normal close")
    }

    suspend fun drainSourceBuffer() {
        val buf = ByteArray(1024)
        while (!source.exhausted()) {
            val read = source.readAtMostTo(buf)
            if (read == -1) break
        }
    }
}

private suspend fun AsyncSource.readFrame(): FrameResult {
    return readFrame(
        readByteArray = { byteCount -> readByteArray(byteCount) },
        readShort = { readShort() },
        readLong = { readLong() },
        readByte = { readByte() },
        readTo = { a, b -> readTo(a, b) },
    )
}

private suspend fun AsyncSink.sendFrame(
    isClient: Boolean,
    isFin: Boolean,
    opcode: Opcode,
    payload: Source,
    payloadLength: ULong,
) {
    sendFrame(
        isClient = isClient,
        isFin = isFin,
        opcode = opcode,
        payload = payload,
        payloadLength = payloadLength,
        writeByte = { writeByte(it) },
        writeShort = { writeShort(it) },
        writeLong = { writeLong(it) },
        write = { a, b, c -> write(a, b, c) },
        writeSource = { a, b -> write(a, b) },
        flush = { flush() }
    )
}

private suspend fun AsyncSource.parseHeaders() = parseHeaders(
    exhausted = { exhausted() },
    readLine = { readLine() }
)

private suspend fun AsyncKioWebSocket.sendMessage(
    type: MessageType,
    payload: Source,
    chunkSize: Long = CHUNK_SIZE
) {
    this as InternalWebSocket
    sendMessage(
        isClient = isClient,
        type = type,
        payload = payload,
        chunkSize = chunkSize,
        sendFrame = { a, b, c, d, e -> asyncSink.sendFrame(a, b, c, d, e) }
    )
}

internal interface KWebSocket {
    val isClient: Boolean

    var needSendCloseEvent: Boolean
}

internal const val CHUNK_SIZE = 8192L
internal const val MAX_CONTROL_FRAME_PAYLOAD_LENGTH = 125U
internal const val CLIENT_SEC_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
internal const val SEC_MAGIC_KEY = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

internal data class FrameResult(
    val isFin: Boolean,
    val rsv1: Boolean,
    val rsv2: Boolean,
    val rsv3: Boolean,
    val opcode: Opcode,
    val payload: Buffer,
    val payloadLength: ULong,
)

// https://datatracker.ietf.org/doc/html/rfc6455#section-5.2
internal enum class Opcode(val code: Int) {
    CONT(0x0),
    TEXT(0x1),
    BIN(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA),
    ;

    companion object {
        fun valueOf(code: Int) = Opcode.entries.firstOrNull { it.code == code }
    }
}

internal fun MessageType.toOpcode() = when (this) {
    MessageType.TEXT -> Opcode.TEXT
    MessageType.BIN -> Opcode.BIN
}

internal fun calculateServerAcceptKey(key: String) =
    Base64.encode(sha1((key + SEC_MAGIC_KEY).encodeToByteArray()))

internal fun Source.checkUtf8Payload() {
    // verify utf8
    while (request(1)) {
        try {
            readCodePointValueOrThrow()
        } catch (_: InvalidUtf8Exception) {
            throw ProtocolException(CloseCode.INVALID_PAYLOAD, "detected invalid utf8 code point.")
        } catch (_: EOFException) {
            // Something wrong with last few bytes!!
            throw ProtocolException(
                CloseCode.INVALID_PAYLOAD,
                "detected invalid utf8 code point in the last bytes."
            )
        }
    }
}

internal fun checkControlFrame(
    opCode: Opcode,
    isFin: Boolean,
    payload: Source,
    payloadLength: ULong
) {
    if (!isFin) {
        throw ProtocolException(
            CloseCode.PROTOCOL_ERROR,
            "Control frame MUST NOT be fragmented. opCode($opCode)"
        )
    }

    if (payloadLength > MAX_CONTROL_FRAME_PAYLOAD_LENGTH) {
        throw ProtocolException(
            CloseCode.PROTOCOL_ERROR,
            "Control frame payload too large. $payloadLength"
        )
    }

    if (opCode == Opcode.CLOSE) {
        if (payloadLength == 1UL) {
            throw ProtocolException(
                closeCode = CloseCode.PROTOCOL_ERROR,
                message = "Close frame payload length 1 is invalid"
            )
        }

        val target = payload.peek()

        // Check close code range.
        if (target.request(2) && !isValidReceivedCloseCode(code = target.readUShort().toInt())) {
            throw ProtocolException(
                closeCode = CloseCode.PROTOCOL_ERROR,
                message = "Invalid close code"
            )
        }
        target.checkUtf8Payload()
    }
}

private fun isValidReceivedCloseCode(code: Int): Boolean {
    return when (code) {
        in 1000..1003 -> true
        in 1007..1014 -> true
        in 3000..4999 -> true
        else -> false
    }
}

internal inline fun serverHandShake(
    parseHeaders: () -> Map<String, String>,
    writeString: (string: String, startIndex: Int, endIndex: Int) -> Unit,
    flush: () -> Unit
) {
    val headers = parseHeaders()
    val secWebsocketKey = headers["Sec-WebSocket-Key"]
        ?: throw IOException("Sec-WebSocket-Key is not set by client")

    val handShake = buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: ${calculateServerAcceptKey(secWebsocketKey)}\r\n")
        append("\r\n")
    }

    writeString(handShake, 0, handShake.length)
    flush()
}

internal inline fun clientHandShake(
    path: String,
    host: String,
    clientSecKey: String = CLIENT_SEC_KEY,
    parseHeaders: () -> Map<String, String>,
    writeString: (string: String, startIndex: Int, endIndex: Int) -> Unit,
    flush: () -> Unit
) {
    val handShake = buildString {
        append("GET $path HTTP/1.1\r\n")
        append("Host: ${host}\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: ${clientSecKey}\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("\r\n")
    }

    writeString(handShake, 0, handShake.length)
    flush()

    val headers = parseHeaders()
    for ((key, value) in headers.entries) {
        when (key) {
            "Sec-WebSocket-Accept" -> {
                if (calculateServerAcceptKey(clientSecKey) != value) {
                    throw IOException("Header value of Sec-WebSocket-Accept(${value}) is not matched to ${clientSecKey}.")
                }
            }
            // If there are other headers to verify...
        }
    }
}

// TODO: Only return headers now, consider return all http information.
internal inline fun parseHeaders(
    exhausted: () -> Boolean,
    readLine: () -> String?,
): Map<String, String> {
    return buildMap {
        while (!exhausted()) {
            val line = readLine()
            if (line.isNullOrEmpty()) break
            if (!line.contains(':')) {
                // not header, continue
                continue
            }

            val (key, value) = line.split(':')
            val headerValue = value.trim()
            put(key, headerValue)
        }
    }
}

internal inline fun readFrame(
    readByteArray: (byteCount: Int) -> ByteArray,
    readShort: () -> Short,
    readLong: () -> Long,
    readByte: () -> Byte,
    readTo: (sink: RawSink, byteCount: Long) -> Unit
): FrameResult {
    val header = readByteArray(2)

    val payloadLength: ULong = when (val len = (header[1] and 0x7F).toInt()) {
        126 -> {
            readShort().toUShort().toULong()
        }

        127 -> {
            readLong().toULong()
        }

        else -> len.toULong()
    }

    // Read the RSV
    val header1 = header[0].toInt()
    val rsv1 = (header1 and 0x40) != 0
    val rsv2 = (header1 and 0x20) != 0
    val rsv3 = (header1 and 0x10) != 0

    // Read the mask
    var mask = ByteArray(4)
    val masked = (header[1].toInt() and 0x80) != 0
    if (masked) {
        mask = readByteArray(4)
    }

    val isFin = (header[0].toInt() and 0x80) != 0
    val opCode = Opcode.valueOf(header[0].toInt() and 0xF)
        ?: throw ProtocolException(
            CloseCode.PROTOCOL_ERROR,
            "received invalid opcode(${header[0].toInt() and 0xF})."
        )

    val buffer = Buffer()

    // Read the payload
    if (masked) {
        var read = 0UL
        while (read < payloadLength) {
            val chunk = ByteArray(1024)
            var chunkSize = 0
            while (read < payloadLength && chunkSize < chunk.size) {
                chunk[chunkSize] = readByte() xor mask[(read % 4UL).toInt()]
                chunkSize++
                read++
            }
            buffer.write(chunk, 0, chunkSize)
        }
    } else {
        readTo(buffer, payloadLength.toLong())
    }

    return FrameResult(isFin, rsv1, rsv2, rsv3, opCode, buffer, payloadLength)
}

@OptIn(ExperimentalUnsignedTypes::class)
internal inline fun sendFrame(
    isClient: Boolean,
    isFin: Boolean,
    opcode: Opcode,
    payload: Source,
    payloadLength: ULong,
    writeByte: (byte: Byte) -> Unit,
    writeShort: (short: Short) -> Unit,
    writeLong: (long: Long) -> Unit,
    write: (source: ByteArray, startIndex: Int, endIndex: Int) -> Unit,
    writeSource: (source: RawSource, byteCount: Long) -> Unit,
    flush: () -> Unit
) {
    // Send FIN and OPCODE
    // NOTE: FIN is always set
    var data: UByte = opcode.code.toUByte()
    if (isFin) data = (data.toInt() or (1 shl 7)).toUByte()
    writeByte(data.toByte())

    // Send masked and payload length
    // NOTE: client frames are always masked
    val maskBit = if (isClient) 0x80 else 0x00
    if (payloadLength < 126UL) {
        val data = (maskBit or payloadLength.toInt()).toUByte()
        writeByte(data.toByte())
    } else if (payloadLength <= UShort.MAX_VALUE.toULong()) {
        val data: UByte = (maskBit or 126).toUByte()
        writeByte(data.toByte())

        writeShort(payloadLength.toShort())
    } else if (payloadLength > UShort.MAX_VALUE.toULong()) {
        val data: UByte = (maskBit or 127).toUByte()
        writeByte(data.toByte())

        writeLong(payloadLength.toLong())
    }

    if (isClient) {
        // Generate and send mask
        val mask = ByteArray(4) {
            (0..255).random().toByte()
        }
        write(mask, 0, mask.size)

        // Mask the payload and send it
        var written = 0UL
        while (written < payloadLength) {
            val chunk = ByteArray(1024)
            var chunkSize = 0
            while (written < payloadLength && chunkSize < chunk.size) {
                chunk[chunkSize] = payload.readByte() xor mask[(written % 4UL).toInt()]
                chunkSize++
                written++
            }
            write(chunk, 0, chunkSize)
        }
    } else {
        writeSource(payload, payloadLength.toLong())
    }

    flush()
}

internal inline fun KWebSocket.sendClose(
    code: CloseCode,
    reason: String? = null,
    sendFrame: (isClient: Boolean, isFin: Boolean, opcode: Opcode, payload: Source, payloadLength: ULong) -> Unit
) {
// TODO: remove all requested fd when close.
    if (!needSendCloseEvent) throw IOException("already send close event.")

    val payload = Buffer().apply {
        writeUShort(code.code.toUShort())
        reason?.let { writeString(it) }
    }

    sendFrame(
        isClient,
        true,
        Opcode.CLOSE,
        payload,
        payload.size.toULong()
    )

    needSendCloseEvent = false
}

internal inline fun KWebSocket.readMessage(
    sink: Sink,
    isClient: Boolean,
    readFrame: () -> FrameResult,
    sendFrame: (isClient: Boolean, isFin: Boolean, opcode: Opcode, payload: Source, payloadLength: ULong) -> Unit
): WebSocketEvent   {
    var messageType: MessageType? = null
    val buffer = Buffer()
    while (true) {
        val (isFin, rsv1, rsv2, rsv3, opCode, payload, payloadLength) = readFrame()

        if (rsv1 || rsv2 || rsv3) throw ProtocolException(CloseCode.PROTOCOL_ERROR, "RSV must be 0")

        when (opCode) {
            Opcode.CLOSE -> {
                checkControlFrame(opCode, isFin, payload, payloadLength)

                sendFrame(isClient, true, Opcode.CLOSE, payload, payloadLength)
                this.needSendCloseEvent = false

                return WebSocketEvent.Close
            }

            Opcode.PING -> {
                checkControlFrame(opCode, isFin, payload, payloadLength)

                sendFrame(isClient, true, Opcode.PONG, payload, payloadLength)
                continue
            }

            Opcode.PONG -> {
                checkControlFrame(opCode, isFin, payload, payloadLength)

                continue
            }

            Opcode.TEXT -> {
                if (messageType != null) throw ProtocolException(
                    CloseCode.PROTOCOL_ERROR,
                    "Send text Message fragmented into 2 fragments, with both frame opcodes set to text."
                )

                messageType = MessageType.TEXT
                buffer.write(payload, payloadLength.toLong())
            }

            Opcode.BIN -> {
                if (messageType != null) throw ProtocolException(
                    CloseCode.PROTOCOL_ERROR,
                    "Send bin Message fragmented into 2 fragments, with both frame opcodes set to bin"
                )

                messageType = MessageType.BIN
                buffer.write(payload, payloadLength.toLong())
            }

            Opcode.CONT -> {
                if (messageType == null) throw ProtocolException(
                    CloseCode.PROTOCOL_ERROR,
                    "Continuation Frame received, but there is nothing to continue"
                )
                buffer.write(payload, payloadLength.toLong())
            }
        }

        if (isFin) break
    }


    if (messageType == MessageType.TEXT) {
        // verify utf8
        buffer.peek().checkUtf8Payload()
    }

    buffer.transferTo(sink)
    return WebSocketEvent.Message(messageType)
}

internal inline fun sendMessage(
    isClient: Boolean,
    type: MessageType,
    payload: Source,
    chunkSize: Long = CHUNK_SIZE,
    sendFrame: (
        isClient: Boolean,
        isFin: Boolean,
        opcode: Opcode,
        payload: Source,
        payloadLength: ULong,
    ) -> Unit
) {
    var isFirst = true

    val buffer = Buffer()
    while (true) {
        val read = payload.readAtMostTo(buffer, chunkSize)

        val isFinal = read == -1L // payload is exhausted

        sendFrame(
            isClient,
            isFinal,
            if (isFirst) type.toOpcode() else Opcode.CONT,
            buffer,
            read.coerceAtLeast(0L).toULong()
        )

        isFirst = false

        if (isFinal) break
    }
}
