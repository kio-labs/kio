package kio.compression

import kio.async.asInMemoryAsyncBuffer
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class InflaterNativeTest {
    @Test
    fun inflaterTest_inflate_by_small_chunk() = runTest {
        val expected = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val sourceByteArray =
            Base64.decode("80jNycnXUfDOL8nJzFPwLc0pySzISSxJyy/KVVQISixXcElNA/JTFTKLFRLLU4vzc1P1AA==")
        val buffer = Buffer()
        val source = InflaterSource(
            Buffer().asInMemoryAsyncBuffer().apply { write(sourceByteArray) },
            windowBits = WindowBits.RAW_DEFLATE,
            bufferChunkSize = 10
        )
        source.readAtMostTo(buffer, Long.MAX_VALUE)
        assertEquals(expected, buffer.readString())
        source.close()
    }
}