package kio.postegre.protocol

import kio.async.AsyncSource
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.Source
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import kotlinx.io.readString

const val PARSE_COMPLETE_TAG: Byte = '1'.code.toByte()
const val BIND_COMPLETE_TAG: Byte = '2'.code.toByte()
const val CLOSE_COMPLETE_TAG: Byte = '3'.code.toByte()
const val NOTIFICATION_RESPONSE_TAG: Byte = 'A'.code.toByte()
const val COPY_DONE_TAG: Byte = 'c'.code.toByte()
const val COMMAND_COMPLETE_TAG: Byte = 'C'.code.toByte()
const val COPY_DATA_TAG: Byte = 'd'.code.toByte()
const val DATA_ROW_TAG: Byte = 'D'.code.toByte()
const val ERROR_RESPONSE_TAG: Byte = 'E'.code.toByte()
const val COPY_IN_RESPONSE_TAG: Byte = 'G'.code.toByte()
const val COPY_OUT_RESPONSE_TAG: Byte = 'H'.code.toByte()
const val EMPTY_QUERY_RESPONSE_TAG: Byte = 'I'.code.toByte()
const val BACKEND_KEY_DATA_TAG: Byte = 'K'.code.toByte()
const val NO_DATA_TAG: Byte = 'n'.code.toByte()
const val NOTICE_RESPONSE_TAG: Byte = 'N'.code.toByte()
const val AUTHENTICATION_TAG: Byte = 'R'.code.toByte()
const val PORTAL_SUSPENDED_TAG: Byte = 's'.code.toByte()
const val PARAMETER_STATUS_TAG: Byte = 'S'.code.toByte()
const val PARAMETER_DESCRIPTION_TAG: Byte = 't'.code.toByte()
const val ROW_DESCRIPTION_TAG: Byte = 'T'.code.toByte()
const val READY_FOR_QUERY_TAG: Byte = 'Z'.code.toByte()

data class Header(
    val tag: Byte,
    val len: Int,
)

data class Field(
    val name: String,
    val tableOid: Int,
    val columnId: Short,
    val typeOid: Int,
    val typeSize: Short,
    val typeModifier: Int,
    val format: Short,
)

sealed class ErrorField(
    open val value: String,
) {
    data class Severity(override val value: String) : ErrorField(value)
    data class SeverityUnlocalized(override val value: String) : ErrorField(value)
    data class Code(override val value: String) : ErrorField(value)
    data class Message(override val value: String) : ErrorField(value)
    data class Detail(override val value: String) : ErrorField(value)
    data class Hint(override val value: String) : ErrorField(value)
    data class Position(override val value: String) : ErrorField(value)
    data class InternalPosition(override val value: String) : ErrorField(value)
    data class InternalQuery(override val value: String) : ErrorField(value)
    data class Where(override val value: String) : ErrorField(value)
    data class SchemaName(override val value: String) : ErrorField(value)
    data class TableName(override val value: String) : ErrorField(value)
    data class ColumnName(override val value: String) : ErrorField(value)
    data class DataTypeName(override val value: String) : ErrorField(value)
    data class ConstraintName(override val value: String) : ErrorField(value)
    data class File(override val value: String) : ErrorField(value)
    data class Line(override val value: String) : ErrorField(value)
    data class Routine(override val value: String) : ErrorField(value)
    data class UnknownFields(override val value: String) : ErrorField(value)

    companion object {
        fun parse(byte: Byte, value: String): ErrorField = when (byte.toInt().toChar()) {
            'S' -> Severity(value)
            'V' -> SeverityUnlocalized(value)
            'C' -> Code(value)
            'M' -> Message(value)
            'D' -> Detail(value)
            'H' -> Hint(value)
            'P' -> Position(value)
            'p' -> InternalPosition(value)
            'q' -> InternalQuery(value)
            'W' -> Where(value)
            's' -> SchemaName(value)
            't' -> TableName(value)
            'c' -> ColumnName(value)
            'd' -> DataTypeName(value)
            'n' -> ConstraintName(value)
            'F' -> File(value)
            'L' -> Line(value)
            'R' -> Routine(value)
            else -> UnknownFields(value)
        }
    }
}

sealed interface Message {
    data object AuthenticationCleartextPassword : Message
    data object AuthenticationGss : Message
    data object AuthenticationKerberosV5 : Message
    class AuthenticationMd5Password(val salt: ByteArray) : Message
    data object AuthenticationOk : Message
    data object AuthenticationScmCredential : Message
    data object AuthenticationSspi : Message
    class AuthenticationGssContinue(val byteArray: ByteArray) : Message
    data class AuthenticationSasl(val mechanisms: String) : Message
    data class AuthenticationSaslContinue(val mechanism: String) : Message
    class AuthenticationSaslFinal(val data: ByteArray) : Message
    class BackendKeyData(val processId: Int, val secretKey: ByteArray) : Message
    data object BindComplete : Message
    data object CloseComplete : Message
    data class CommandComplete(val tag: String) : Message
    class CopyData(val data: ByteArray) : Message
    data object CopyDone : Message
    class CopyInResponse(val format: Byte, val columnFormats: Iterable<Short>) : Message
    class CopyOutResponse(val format: Byte, val columnFormats: Iterable<Short>) : Message
    class DataRow(val byteArray: ByteArray) : Message
    data object EmptyQueryResponse : Message
    class ErrorResponse(val errors: Iterable<ErrorField>) : Message
    data object NoData : Message
    class NoticeResponse(val errors: Iterable<ErrorField>) : Message
    data class NotificationResponse(val processId: Int, val channel: String, val message: String) :
        Message

    class ParameterDescription(val oids: Iterable<Int>) : Message
    data class ParameterStatus(val name: String, val value: String) : Message
    data object ParseComplete : Message
    data object PortalSuspended : Message
    data class ReadyForQuery(val status: Byte) : Message
    class RowDescription(val fields: Iterable<Field>) : Message
}

suspend fun AsyncSource.readHeader(): Header {
    val tag = readByte()
    val len = readInt()
    if (len < 4) {
        throw IOException("invalid message length: header length < 4")
    }

    return Header(tag, len)
}

suspend fun AsyncSource.readMessage(): Message {
    val tag = readByte()
    val len = readInt()
    if (len < 4) {
        throw IOException("invalid message length: parsing u32")
    }

    val buffer = Buffer()
    readTo(buffer, (len - 4).toLong())

    return when (tag) {
        PARSE_COMPLETE_TAG -> Message.ParseComplete
        BIND_COMPLETE_TAG -> Message.BindComplete
        CLOSE_COMPLETE_TAG -> Message.CloseComplete
        NOTIFICATION_RESPONSE_TAG -> Message.NotificationResponse(
            processId = buffer.readInt(),
            channel = buffer.readCString() ?: throw IOException("Not a valid C string"),
            message = buffer.readCString() ?: throw IOException("Not a valid C string"),
        )

        COPY_DONE_TAG -> Message.CopyDone
        COMMAND_COMPLETE_TAG -> Message.CommandComplete(
            buffer.readCString() ?: throw IOException("Not a valid C string")
        )

        COPY_DATA_TAG -> Message.CopyData(buffer.readByteArray())
        DATA_ROW_TAG -> Message.DataRow(buffer.readByteArray())
        ERROR_RESPONSE_TAG -> Message.ErrorResponse(errorFieldIterable(buffer))
        COPY_IN_RESPONSE_TAG -> Message.CopyInResponse(
            format = buffer.readByte(),
            columnFormats = columnFormatsIterator(buffer)
        )

        COPY_OUT_RESPONSE_TAG -> Message.CopyInResponse(
            format = buffer.readByte(),
            columnFormats = columnFormatsIterator(buffer)
        )

        EMPTY_QUERY_RESPONSE_TAG -> Message.EmptyQueryResponse
        BACKEND_KEY_DATA_TAG -> Message.BackendKeyData(
            processId = buffer.readInt(),
            secretKey = buffer.readByteArray(),
        )

        NO_DATA_TAG -> Message.NoData
        NOTICE_RESPONSE_TAG -> Message.NoticeResponse(errorFieldIterable(buffer))
        AUTHENTICATION_TAG -> when (buffer.readInt()) {
            0 -> Message.AuthenticationOk
            2 -> Message.AuthenticationKerberosV5
            3 -> Message.AuthenticationCleartextPassword
            5 -> Message.AuthenticationMd5Password(buffer.readByteArray(4))
            6 -> Message.AuthenticationScmCredential
            7 -> Message.AuthenticationGss
            8 -> Message.AuthenticationGssContinue(buffer.readByteArray())
            9 -> Message.AuthenticationSspi
            10 -> Message.AuthenticationSasl(buffer.readByteArray().toKString())
            11 -> Message.AuthenticationSaslContinue(buffer.readByteArray().toKString())
            12 -> Message.AuthenticationSaslFinal(buffer.readByteArray())
            else -> throw IOException("unknown authentication tag $tag")
        }

        PORTAL_SUSPENDED_TAG -> Message.PortalSuspended
        PARAMETER_STATUS_TAG -> Message.ParameterStatus(
            name = buffer.readCString() ?: throw IOException("Not a valid String"),
            value = buffer.readCString() ?: throw IOException("Not a valid String"),
        )

        PARAMETER_DESCRIPTION_TAG -> Message.ParameterDescription(oidIterable(buffer))
        ROW_DESCRIPTION_TAG -> Message.RowDescription(fieldIterable(buffer))
        READY_FOR_QUERY_TAG -> Message.ReadyForQuery(buffer.readByte())
        else -> {
            throw IOException("unknown message tag ${tag}")
        }
    }
}

private fun errorFieldIterable(buffer: Buffer) = object : Iterable<ErrorField> {
    override fun iterator(): Iterator<ErrorField> = iterator {
        val source = buffer.peek()
        while (!source.exhausted()) {
            val type = source.readByte()

            if (type == 0.toByte()) break
            val value = source.readCString()
                ?: throw IOException("Invalid ErrorResponse: missing null-terminated string")

            yield(ErrorField.parse(type, value))
        }
    }
}

private fun fieldIterable(buffer: Buffer) = object : Iterable<Field> {
    override fun iterator(): Iterator<Field> = iterator {
        val source = buffer.peek()
        val len = source.readShort().toInt()

        repeat(len) { i ->
            val filed = Field(
                name = source.readCString() ?: throw IOException("Not a valid String"),
                tableOid = source.readInt(),
                columnId = source.readShort(),
                typeOid = source.readInt(),
                typeSize = source.readShort(),
                typeModifier = source.readInt(),
                format = source.readShort()
            )

            yield(filed)
        }

        if (!source.exhausted()) throw IOException("invalid message length: field is not drained")
    }
}

private fun oidIterable(buffer: Buffer) = object : Iterable<Int> {
    override fun iterator(): Iterator<Int> = iterator {
        val source = buffer.peek()
        val len = source.readShort().toInt()

        repeat(len) {
            yield(source.readInt())
        }

        if (!source.exhausted()) throw IOException("invalid message length: oid is not drained")
    }
}

@OptIn(UnsafeByteStringApi::class)
private fun columnFormatsIterator(buffer: Buffer) = object : Iterable<Short> {
    override fun iterator(): Iterator<Short> = iterator {
        val source = buffer.peek()
        val len = source.readShort().toInt()

        repeat(len) {
            yield(source.readShort())
        }

        if (!source.exhausted()) throw IOException("invalid message length: column formats is not drained")
    }
}


@OptIn(InternalIoApi::class)
private fun Source.readCString(): String? {
    if (!request(1)) return null

    var lfIndex = this.indexOf(0.toByte())
    return when (lfIndex) {
        -1L -> readString()
        0L -> {
            skip(1)
            ""
        }

        else -> {
            val string = readString(lfIndex)
            skip(1)
            string
        }
    }
}

private fun ByteArray.toKString() : String {
    val realEndIndex = realEndIndex(this, 0, this.size)
    return decodeToString(0, realEndIndex)
}

private fun realEndIndex(byteArray: ByteArray, startIndex: Int, endIndex: Int): Int {
    var index = startIndex
    while (index < endIndex && byteArray[index] != 0.toByte()) {
        index++
    }
    return index
}
