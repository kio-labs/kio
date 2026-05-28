package kio.compression

import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class InflaterNativeTest {
    @Test
    fun inflaterTest_inflate_by_small_chunk() {
        val expected = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val sourceByteArray =
            Base64.decode("80jNycnXUfDOL8nJzFPwLc0pySzISSxJyy/KVVQISixXcElNA/JTFTKLFRLLU4vzc1P1AA==")
        val buffer = Buffer()
        val source = InflaterSource(
            Buffer().apply { write(sourceByteArray) },
            windowBits = WindowBits.RAW_DEFLATE,
            bufferChunkSize = 10
        )
        source.readAtMostTo(buffer, Long.MAX_VALUE)
        assertEquals(expected, buffer.readString())
        source.close()
    }
}