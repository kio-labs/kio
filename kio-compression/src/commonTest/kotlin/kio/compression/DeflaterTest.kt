package kio.compression

import kio.async.asInMemoryAsyncBuffer
import kio.async.buffered
import kio.async.readByteString
import kio.async.writeString
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.bytestring.decodeToByteString
import kotlinx.io.readByteString
import kotlinx.io.writeString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class DeflaterCommonTest {
    @Test
    fun deflaterTest_compress_wrap() = runTest {
        val content = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val target = Buffer().asInMemoryAsyncBuffer()
        val deflater = target.zlibSink(level = 8).buffered()
        deflater.writeString(content)
        deflater.close()
        assertEquals(
            Base64.decodeToByteString("eNrzSM3JyddR8M4vycnMU/AtzSnJLMhJLEnLL8pVVAhKLFdwSU0D8lMVMosVEstTi/NzU/UA8K4SvQ=="),
            target.readByteString()
        )
    }

    @Test
    fun deflaterTest_compress_no_wrap() = runTest {
        val content = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val target = Buffer().asInMemoryAsyncBuffer()
        val deflater = target.deflateSink(level = 8).buffered()
        deflater.writeString(content)
        deflater.close()
        assertEquals(
            Base64.decodeToByteString("80jNycnXUfDOL8nJzFPwLc0pySzISSxJyy/KVVQISixXcElNA/JTFTKLFRLLU4vzc1P1AA=="),
            target.readByteString()
        )
    }

    @Test
    fun deflaterTest_compress_in_parts() = runTest {
        val contentA = "Hello, Kotlin Multiplat"
        val contentB = "form! Raw Deflate is awesome."

        val target = Buffer().asInMemoryAsyncBuffer()
        val deflater = target.zlibSink(level = 8).buffered()
        deflater.writeString(contentA)
        deflater.writeString(contentB)
        deflater.close()

        assertEquals(
            Base64.decodeToByteString("eNrzSM3JyddR8M4vycnMU/AtzSnJLMhJLEnLL8pVVAhKLFdwSU0D8lMVMosVEstTi/NzU/UA8K4SvQ=="),
            target.readByteString(),
        )
    }
}