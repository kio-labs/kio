package kio.postgres.protocol

import kio.async.AsyncSink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun AsyncSink.writeStartupMessage(params: Map<String, String>) = withContext(NonCancellable) {
    writeBody(
        bodySize = {
            var paramSize = 0
            params.forEach { (key, value) ->
                paramSize += cStringSize(key) + cStringSize(value)
            }
            SHORT_SIZE * 2 + paramSize + BYTE_SIZE
        },
        body = {
            writeShort(3)
            writeShort(0)

            params.forEach { (key, value) ->
                writeCString(key)
                writeCString(value)
            }

            writeByte(0)
        }
    )
}

suspend fun AsyncSink.writeStartTlsMessage() = withContext(NonCancellable) {
    writeInt(8)
    writeInt(80877103)
}

suspend fun AsyncSink.writeSASLInitialResponse(mechanism: String, data: ByteArray) = withContext(NonCancellable) {
    writeByte('p'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(mechanism) + INT_SIZE + data.size
        },
        body = {
            writeCString(mechanism)
            if (data.isEmpty()) writeInt(-1) else writeInt(data.size)
            if (data.isNotEmpty()) write(data)
        }
    )
}

suspend fun AsyncSink.writeSASLResponse(data: ByteArray) = withContext(NonCancellable) {
    writeByte('p'.code.toByte())
    writeInt(data.size + 4)
    write(data)
}

suspend fun AsyncSink.writeParse(name: String, query: String, paramTypes: List<Int>) = withContext(NonCancellable) {
    writeByte('P'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(name) + cStringSize(query) + countedListSize(paramTypes, INT_SIZE)
        },
        body = {
            writeCString(name)
            writeCString(query)
            writeCountedList(paramTypes) { type ->
                writeInt(type)
            }
        }
    )
}

suspend fun AsyncSink.writeDescribeStatement(stm: String) {
    writeDescribe('S', stm)
}

suspend fun AsyncSink.writeDescribePortal(portal: String) {
    writeDescribe('P', portal)
}

private suspend fun AsyncSink.writeDescribe(variant: Char, name: String) = withContext(NonCancellable) {
    writeByte('D'.code.toByte())
    writeBody(
        bodySize = {
            BYTE_SIZE + cStringSize(name)
        },
        body = {
            writeByte(variant.code.toByte())
            writeCString(name)
        }
    )
}

suspend fun AsyncSink.writeCloseStatement(stm: String) {
    writeClose('S', stm)
}

suspend fun AsyncSink.writeClosePortal(portal: String) {
    writeClose('P', portal)
}

private suspend fun AsyncSink.writeClose(variant: Char, name: String) = withContext(NonCancellable) {
    writeByte('C'.code.toByte())
    writeBody(
        bodySize = {
            BYTE_SIZE + cStringSize(name)
        },
        body = {
            writeByte(variant.code.toByte())
            writeCString(name)
        }
    )
}

suspend fun AsyncSink.writeBind(
    portal: String = "",
    statement: String = "",
    formats: List<Short>,
    values: ByteArray,
    resultFormat: List<Short>
) = withContext(NonCancellable) {
    writeByte('B'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(portal) + cStringSize(statement) + countedListSize(formats, SHORT_SIZE) + values.size + countedListSize(resultFormat, SHORT_SIZE)
        },
        body = {
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
    )
}

suspend fun AsyncSink.writePassword(password: String) = withContext(NonCancellable) {
    writeByte('p'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(password)
        },
        body = {
            writeCString(password)
        }
    )
}

suspend fun AsyncSink.writeExecute(portal: String, maxRows: Int = 0) = withContext(NonCancellable) {
    writeByte('E'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(portal) + INT_SIZE
        },
        body = {
            writeCString(portal)
            writeInt(maxRows)
        }
    )
}

suspend fun AsyncSink.writeSync() = withContext(NonCancellable) {
    writeByte('S'.code.toByte())
    writeBody({ 0 }, {})
}

suspend fun AsyncSink.writeCopyDone() = withContext(NonCancellable) {
    writeByte('c'.code.toByte())
    writeBody({ 0 }, {})
}

suspend fun AsyncSink.writeCopyFail(msg: String) = withContext(NonCancellable) {
    writeByte('f'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(msg)
        },
        body = {
            writeCString(msg)
        }
    )
}

suspend fun AsyncSink.writeFlush() = withContext(NonCancellable) {
    writeByte('H'.code.toByte())
    writeBody({ 0 }, {})
}

suspend fun AsyncSink.writeTerminate() = withContext(NonCancellable) {
    writeByte('X'.code.toByte())
    writeBody({ 0 }, {})
}

private const val cancelRequestCode = 80877102

suspend fun AsyncSink.writeCancelRequest(processId: Int, secretKey: ByteArray) = withContext(NonCancellable) {
    if (secretKey.size > 256) error("secret key too long")

    val len = 12 + secretKey.size
    writeInt(len)
    writeInt(cancelRequestCode)
    writeInt(processId)
    write(secretKey)
}

suspend fun AsyncSink.writeQuery(query: String) = withContext(NonCancellable) {
    writeByte('Q'.code.toByte())
    writeBody(
        bodySize = {
            cStringSize(query)
        },
        body = {
            writeCString(query)
        }
    )
}

private suspend inline fun AsyncSink.writeBody(
    crossinline bodySize: () -> Int,
    crossinline body: suspend () -> Unit
) = withContext(NonCancellable) {
    writeInt(bodySize() + 4)
    body()
}

private suspend inline fun <T> AsyncSink.writeCountedList(list: List<T>, writeItem: (T) -> Unit) {
    writeShort(list.size.toShort())
    list.onEach(writeItem)
}

private suspend fun AsyncSink.writeCString(value: String) {
    write(value.encodeToByteArray())
    writeByte(0)
}

private const val SHORT_SIZE = 2
private const val INT_SIZE = 4
private const val BYTE_SIZE = 1
private fun cStringSize(value: String) = value.length + 1
private fun <T> countedListSize(list: List<T>, itemSize: Int) = SHORT_SIZE + list.size * itemSize
