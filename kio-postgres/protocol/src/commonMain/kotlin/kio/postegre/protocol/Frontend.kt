package kio.postegre.protocol

import kio.async.AsyncSink
import kotlinx.io.Buffer
import kotlinx.io.Sink

suspend fun AsyncSink.writeStartupMessage(params: Map<String, String>) {
    writeBody {
        writeShort(3)
        writeShort(0)

        params.forEach { (key, value) ->
            writeCString(key)
            writeCString(value)
        }

        writeByte(0)
    }
}

suspend fun AsyncSink.writeParse(name: String, query: String, paramTypes: List<Int>) {
    writeByte('P'.code.toByte())
    writeBody {
        writeCString(name)
        writeCString(query)
        writeCountedList(paramTypes) { type ->
            writeInt(type)
        }
    }
}

suspend fun AsyncSink.writeDescribeStatement(stm: String) {
    writeDescribe('S', stm)
}

suspend fun AsyncSink.writeDescribePortal(portal: String) {
    writeDescribe('P', portal)
}

private suspend fun AsyncSink.writeDescribe(variant: Char, name: String) {
    writeByte('D'.code.toByte())
    writeBody {
        writeByte(variant.code.toByte())
        writeCString(name)
    }
}

suspend fun AsyncSink.writeCloseStatement(stm: String) {
    writeClose('S', stm)
}

suspend fun AsyncSink.writeClosePortal(portal: String) {
    writeClose('P', portal)
}

private suspend fun AsyncSink.writeClose(variant: Char, name: String) {
    writeByte('C'.code.toByte())
    writeBody {
        writeByte(variant.code.toByte())
        writeCString(name)
    }
}

suspend fun AsyncSink.writeBind(
    portal: String = "",
    statement: String = "",
    formats: List<Short>,
    values: ByteArray,
    resultFormat: List<Short>
) {
    writeByte('B'.code.toByte())
    writeBody {
        writeCString(portal)
        writeCString(statement)
        writeCountedList(formats) { format ->
            writeShort(format)
        }

        write(values)

        writeCountedList(resultFormat) { resFormat ->
            writeShort(resFormat)
        }
    }
}

suspend fun AsyncSink.writePassword(password: String) {
    writeByte('p'.code.toByte())
    writeBody {
        writeCString(password)
    }
}

suspend fun AsyncSink.writeExecute(portal: String, maxRows: Int = 0) {
    writeByte('E'.code.toByte())
    writeBody {
        writeCString(portal)
        writeInt(maxRows)
    }
}


suspend fun AsyncSink.writeSync() {
    writeByte('S'.code.toByte())
    writeBody {}
}

suspend fun AsyncSink.writeCopyDone() {
    writeByte('c'.code.toByte())
    writeBody {}
}

suspend fun AsyncSink.writeCopyFail(msg: String) {
    writeByte('f'.code.toByte())
    writeBody {
        writeCString(msg)
    }
}

suspend fun AsyncSink.writeFlush() {
    writeByte('H'.code.toByte())
    writeBody {}
}

suspend fun AsyncSink.writeTerminate() {
    writeByte('X'.code.toByte())
    writeBody {}
}

private const val cancelRequestCode = 80877102
suspend fun AsyncSink.writeCancelRequest(processId: Int, secretKey: ByteArray) {
    if (secretKey.size > 256) error("secret key too long")

    val len = 12 + secretKey.size
    writeInt(len)
    writeInt(cancelRequestCode)
    writeInt(processId)
    write(secretKey)
}

suspend fun AsyncSink.writeQuery(query: String) {
    writeByte('Q'.code.toByte())
    writeBody { writeCString(query) }
}

private suspend inline fun AsyncSink.writeBody(
    crossinline buildBody: Buffer.() -> Unit
) {
    val buf = Buffer()

    buildBody(buf)

    writeInt(buf.size.toInt() + 4)
    write(buf, buf.size)
}

private inline fun <T> Sink.writeCountedList(list: List<T>, writeItem: (T) -> Unit) {
    writeShort(list.size.toShort())
    list.onEach(writeItem)
}

private fun Sink.writeCString(value: String) {
    write(value.encodeToByteArray())
    writeByte(0)
}
