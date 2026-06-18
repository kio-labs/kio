package kio.http.internal.http1

import kio.async.buffered
import kio.async.inMemoryAsyncBuffer
import kio.async.readString
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChunkedSourceTest {
    @Test
    fun smokeTest() = runTest {
        val buffer =
            Buffer().apply { writeString("1\r\nh\r\n1\r\ne\r\n1\r\nl\r\n1\r\nl\r\n1\r\no\r\n0\r\n\r\n") }
                .inMemoryAsyncBuffer()
        assertEquals("hello", ChunkedSource(buffer).buffered().readString())
    }

    @Test
    fun invalidChunkCountTest() = runTest {
        val buffer =
            Buffer().apply { writeString("1adf\r\nh\r\n1\r\ne\r\n1\r\nl\r\n1\r\nl\r\n1\r\no\r\n0\r\n\r\n") }
                .inMemoryAsyncBuffer()
        assertFails("invalid chunk count (1adf).") {
            ChunkedSource(buffer).buffered().readString()
        }
    }

    @Test
    fun chunkCanBeBufferedInInternalBuffer() = runTest {
        val buffer = Buffer().apply { writeString("3\r\nhel\r\n1\r\nl\r\n1\r\no\r\n0\r\n\r\n") }
            .inMemoryAsyncBuffer()
        val source = ChunkedSource(buffer)
        val sink = Buffer()
        source.readAtMostTo(sink, 1)
        assertEquals('h'.code.toByte(), sink.readByte())
        source.readAtMostTo(sink, 1)
        assertEquals('e'.code.toByte(), sink.readByte())
        source.readAtMostTo(sink, 1)
        assertEquals('l'.code.toByte(), sink.readByte())
        source.readAtMostTo(sink, 1)
        assertEquals('l'.code.toByte(), sink.readByte())
        source.readAtMostTo(sink, 1)
        assertEquals('o'.code.toByte(), sink.readByte())
        assertEquals(-1, source.readAtMostTo(sink, 1))
    }

    @Test
    fun skipRemainingTest() = runTest {
        val buffer = Buffer().apply { writeString("3\r\nhel\r\n1\r\nl\r\n1\r\no\r\n0\r\n\r\n") }
            .inMemoryAsyncBuffer()
        val source = ChunkedSource(buffer)
        val sink = Buffer()
        source.readAtMostTo(sink, 1)
        assertEquals('h'.code.toByte(), sink.readByte())
        source.drain()
        assertEquals(-1, source.readAtMostTo(sink, 1))
    }
}