package kio.async

import kotlinx.io.InternalIoApi
import kotlinx.io.readTo

public suspend fun AsyncSource.readByteArray(byteCount: Int): ByteArray {
    checkByteCount(byteCount.toLong())
    return readByteArrayImpl(byteCount)
}

@OptIn(InternalIoApi::class)
private suspend  fun AsyncSource.readByteArrayImpl(size: Int): ByteArray {
    var arraySize = size
    if (size == -1) {
        var fetchSize = Int.MAX_VALUE.toLong()
        while (buffer.size < Int.MAX_VALUE && request(fetchSize)) {
            fetchSize *= 2
        }
        check(buffer.size < Int.MAX_VALUE) { "Can't create an array of size ${buffer.size}" }
        arraySize = buffer.size.toInt()
    } else {
        require(size.toLong())
    }
    val array = ByteArray(arraySize)
    buffer.readTo(array)
    return array
}