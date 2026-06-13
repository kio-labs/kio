package kio.compression

import kio.async.asInMemoryAsyncBuffer
import kio.async.buffered
import kio.async.readByteString
import kio.async.writeString
import kio.compression.DeflaterSink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.bytestring.decodeToByteString
import kotlinx.io.readByteString
import kotlinx.io.writeString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class DeflaterNativeTest {
    @Test
    fun deflaterTest_compress_with_small_chunk() = runTest {
        val content = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val target = Buffer().asInMemoryAsyncBuffer()
        val deflater = DeflaterSink(target, 8, windowBits = WindowBits.ZLIB, bufferChunkSize = 10).buffered()
        deflater.writeString(content)
        deflater.close()
        assertEquals(
            Base64.decodeToByteString("eNrzSM3JyddR8M4vycnMU/AtzSnJLMhJLEnLL8pVVAhKLFdwSU0D8lMVMosVEstTi/NzU/UA8K4SvQ=="),
            target.readByteString()
        )
    }
}