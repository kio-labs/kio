package kio.compression

import kio.compression.DeflaterSink
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
    fun deflaterTest_compress_with_small_chunk() {
        val content = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val target = Buffer()
        val deflater = DeflaterSink(target, 8, windowBits = WindowBits.ZLIB, bufferChunkSize = 10).buffered()
        deflater.writeString(content)
        deflater.close()
        assertEquals(
            Base64.decodeToByteString("eNrzSM3JyddR8M4vycnMU/AtzSnJLMhJLEnLL8pVVAhKLFdwSU0D8lMVMosVEstTi/NzU/UA8K4SvQ=="),
            target.readByteString()
        )
    }
}