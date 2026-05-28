package kio.compression

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

class GzipTest {
    @Test
    fun gzipTest_compress_and_decompress() {
        val content = "Hello, Kotlin Multiplatform! Raw Deflate is awesome."
        val compressedBuffer = Buffer()

        val gzipSink = compressedBuffer.gzipSink(level = 8).buffered()
        gzipSink.writeString(content)
        gzipSink.close()

        val peekSource = compressedBuffer.peek()
        assertEquals(0x1f.toByte(), peekSource.readByte(), "Missing GZIP magic byte 1")
        assertEquals(0x8b.toByte(), peekSource.readByte(), "Missing GZIP magic byte 2")

        val decompressedBuffer = Buffer()
        val gzipSource = compressedBuffer.gzipSource()
        gzipSource.readAtMostTo(decompressedBuffer, Long.MAX_VALUE)
        gzipSource.close()

        assertEquals(content, decompressedBuffer.readString())
    }
}