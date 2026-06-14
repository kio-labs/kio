package kio.compression

import kio.async.inMemoryAsyncBuffer
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class InflaterCommonTest {
    @Test
    fun inflaterTest_wrap() = runTest {
        val expected = "Your test string, 1234567890, asdflgjqweroiutyjbioasdkljb"
        val sourceByteArray =
            Base64.decode("eJyLzC8tUihJLS5RKC4pysxL11EwNDI2MTUzt7A00FFILE5Jy0nPKixPLcrPLC2pzErKzAeKZedkJQEAMAMUPw==")
        val buffer = Buffer()
        val source = Buffer().inMemoryAsyncBuffer().apply { write(sourceByteArray) }.zlibSource()
        source.readAtMostTo(buffer, Long.MAX_VALUE)
        assertEquals(expected, buffer.readString())
        source.close()
    }

    @Test
    fun inflaterTest_no_wrap() = runTest {
        val expected = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val sourceByteArray =
            Base64.decode("80jNycnXUfDOL8nJzFPwLc0pySzISSxJyy/KVVQISixXcElNA/JTFTKLFRLLU4vzc1P1AA==")
        val buffer = Buffer()
        val source = Buffer().inMemoryAsyncBuffer().apply { write(sourceByteArray) }.deflateSource()
        source.readAtMostTo(buffer, Long.MAX_VALUE)
        assertEquals(expected, buffer.readString())
        source.close()
    }

    @Test
    fun inflaterTest_read_by_char() = runTest {
        val sourceByteArray = Base64.decode("c3QcBaNgFAx3AAA=")
        val buffer = Buffer()
        val source = Buffer().inMemoryAsyncBuffer().apply { write(sourceByteArray) }.deflateSource()
        repeat(1000) {
            assertEquals(1, source.readAtMostTo(buffer, 1))
            assertEquals("A", buffer.readString())
        }
        assertEquals(-1, source.readAtMostTo(buffer, 1))
        source.close()
    }
}