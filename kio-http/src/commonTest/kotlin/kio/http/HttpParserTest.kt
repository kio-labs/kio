package kio.http

import kio.async.InMemoryAsyncBuffer
import kio.async.writeString
import kotlinx.coroutines.test.runTest
import kotlinx.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.text.get


class HttpParserTest {
    @Test
    fun parseStatusCodeShouldBeValid() = runTest {
        listOf(
            "HTTP/1.1 100 OK\r\n\r\n",
            "HTTP/1.1 999 OK\r\n\r\n",
        ).forEach {
            val buffer = inMemoryAsyncBuffer(it)
            val response = buffer.parseResponse()
            assertEquals("OK", response.statusText.toString())
        }
    }

    @Test
    fun parseStatusCodeShouldFailWhenOutOfRange() = runTest {
        assertFailsWith<ParserException> {
            inMemoryAsyncBuffer("HTTP/1.1 0 OK\r\n").parseResponse()
        }
        assertFailsWith<ParserException> {
            inMemoryAsyncBuffer("HTTP/1.1 99 OK\r\n").parseResponse()
        }
        assertFailsWith<ParserException> {
            inMemoryAsyncBuffer("HTTP/1.1 1000 OK\r\n").parseResponse()
        }
    }

    @Test
    fun parseStatusCodeShouldFailWhenStatusCodeIsNegative() = runTest {
        assertFailsWith<NumberFormatException> {
            inMemoryAsyncBuffer("HTTP/1.1 -100 OK\r\n").parseResponse()
        }
    }

    @Test
    fun testInvalidResponse() = runTest {
        val cases = listOf("A", "H", "a")

        for (case in cases) {
            assertFailsWith<EOFException> {
                inMemoryAsyncBuffer(case + "\r\n").parseResponse()
            }
        }
    }

    @Test
    fun readHeadersSmokeTest() = runTest {
        val hh = inMemoryAsyncBuffer("Host: localhost\r\n\r\n").readHeaders()
        assertEquals("localhost", hh["Host"])
        assertEquals("localhost", hh["host"])
        assertEquals("localhost", hh["hOst"])
        assertEquals("localhost", hh["HOST"])
    }
}

private suspend fun inMemoryAsyncBuffer(str: String) =
    InMemoryAsyncBuffer().apply { writeString(str) }
