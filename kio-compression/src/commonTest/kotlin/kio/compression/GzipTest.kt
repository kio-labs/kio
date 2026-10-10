package kio.compression

import kio.async.buffered
import kio.async.inMemoryAsyncBuffer
import kio.async.writeString
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

class GzipCommonTest {
    @Test
    fun gzipTest_roundTrip() = runTest {
        val content = "Hello, Kotlin Multiplatform! Gzip 压缩测试。"
        val compressed = Buffer().inMemoryAsyncBuffer()
        val sink = compressed.gzipSink().buffered()
        sink.writeString(content)
        sink.close()

        val source = compressed.gzipSource()
        val result = Buffer()
        try {
            while (source.readAtMostTo(result, 8192) != -1L) {
                // Read the complete stream, including the gzip trailer.
            }
            assertEquals(content, result.readString())
        } finally {
            source.close()
        }
    }
}
