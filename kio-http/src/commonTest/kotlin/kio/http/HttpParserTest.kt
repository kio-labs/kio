/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package kio.http

import io.ktor.http.HttpMethod
import io.ktor.http.parsing.ParseException
import kio.async.InMemoryAsyncBuffer
import kio.async.writeString
import kotlinx.coroutines.test.runTest
import kotlinx.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.toString

private const val HTAB: Char = '\u0009'

class HttpParserTest {
    @Test
    fun parseStatusCodeShouldBeValid() = runTest {
        listOf(
            "HTTP/1.1 100 OK\r\n\r\n",
            "HTTP/1.1 999 OK\r\n\r\n",
        ).forEach {
            val buffer = inMemoryAsyncBuffer(it)
            val response = buffer.parseResponse()
            assertEquals("OK", response.statusText)
        }
    }

    @Test
    fun parseStatusCodeShouldFailWhenOutOfRange() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("HTTP/1.1 0 OK\r\n\r\n").parseResponse()
        }
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("HTTP/1.1 99 OK\r\n\r\n").parseResponse()
        }
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("HTTP/1.1 1000 OK\r\n\r\n").parseResponse()
        }
    }

    @Test
    fun parseStatusCodeShouldFailWhenStatusCodeIsNegative() = runTest {
        assertFailsWith<NumberFormatException> {
            inMemoryAsyncBuffer("HTTP/1.1 -100 OK\r\n\r\n").parseResponse()
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
    fun testParseVersion() = runTest {
        val cases = listOf(
            "GET / HTTP/1.6\r\nHost: www.example.com\r\n\r\n",
            "GET / HTPT/1.1\r\nHost: www.example.com\r\n\r\n",
            "GET / _\r\nHost: www.example.com\r\n\r\n",
            "GET / HTTP/1.11\r\nHost: www.example.com\r\n\r\n",
        )

        for (case in cases) {
            assertFails {
                println(inMemoryAsyncBuffer(case).parseRequest().version)
            }
        }
    }

    @Test
    fun testParseGetRoot() = runTest {
        val requestText = "GET / HTTP/1.1\r\nHost:  localhost\r\nConnection:close\r\n\r\n"
        val request = inMemoryAsyncBuffer(requestText).parseRequest()
        assertNotNull(request)
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/", request.uri)
        assertEquals("HTTP/1.1", request.version.toString())

        assertEquals(2, request.headers.entries().size)
        assertEquals("localhost", request.headers["Host"])
        assertEquals("close", request.headers["Connection"])
    }

    @Test
    fun readHeadersSmokeTest() = runTest {
        val hh = inMemoryAsyncBuffer("Host: localhost\r\n\r\n").readHeaders()
        assertEquals("localhost", hh["Host"])
        assertEquals("localhost", hh["host"])
        assertEquals("localhost", hh["hOst"])
        assertEquals("localhost", hh["HOST"])
    }

    @Test
    fun readHeadersSmokeTestUnicode() = runTest {
        val hh = inMemoryAsyncBuffer("Host: unicode-\u0422\r\n\r\n").readHeaders()
        assertEquals("unicode-\u0422", hh["Host"])
    }

    @Test
    fun readHeadersExtraSpacesLeading() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer(" Host:  localhost\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun readHeadersExtraSpacesMiddle() = runTest {
        val hh = inMemoryAsyncBuffer("Host:  localhost\r\n\r\n").readHeaders()
        assertEquals("localhost", hh["Host"])
    }

    @Test
    fun readHeadersExtraSpacesMiddleBeforeColon() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("Host : localhost\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun readHeadersExtraSpacesMiddleBeforeColonNoAfter() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("Host :localhost\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun readHeadersExtraSpacesTrailing() = runTest {
        val hh = inMemoryAsyncBuffer("Host:  localhost \r\n\r\n").readHeaders()
        assertEquals("localhost", hh["Host"])
    }

    @Test
    fun testNoColon() = runTest {
        assertFails {
            inMemoryAsyncBuffer("Host\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun testBlankHeaderValue() = runTest {
        val hh = inMemoryAsyncBuffer("Host: \r\n\r\n").readHeaders()
        assertEquals("", hh["Host"])
    }


    @Test
    fun testWrongHeader() = runTest {
        assertFails {
            inMemoryAsyncBuffer("Hello world\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun testHostHeaderWithInvalidCharacter_slash() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("Host: www/exam/ple.com\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun testHostHeaderWithInvalidCharacter_questionMark() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("Host: www.example?com\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun testHostHeaderWithInvalidCharacter_hash() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("Host: www.ex#mple.com\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun testHostHeaderWithInvalidCharacter_at() = runTest {
        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer("Host: www.ex@mple.com\r\n\r\n").readHeaders()
        }
    }

    @Test
    fun parseHeadersSmokeTest() = runTest {
        val encodedHeaders = "name: value\r\nname2:${HTAB}p1${HTAB}p2 p3${HTAB}\r\n\r\n"
        val headers = inMemoryAsyncBuffer(encodedHeaders).readHeaders()

        assertEquals(2, headers.entries().size)
        assertEquals("value", headers["name"].toString())
        assertEquals("p1${HTAB}p2 p3", headers["name2"].toString())
    }

    @Test
    fun parseHeadersNoLeadingSpace() = runTest {
        val encodedHeaders = "name:value\r\n\r\n"
        val headers = inMemoryAsyncBuffer(encodedHeaders).readHeaders()

        assertEquals(1, headers.entries().size)
        assertEquals("value", headers["name"].toString())
    }

    @Test
    fun parseHeadersNoLeadingSpaceWithTrailingSpaces() = runTest {
        val encodedHeaders = "name:value    \r\n\r\n"
        val headers = inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        assertEquals(1, headers.entries().size)
        assertEquals("value", headers["name"].toString())
    }

    @Test
    fun parseHeadersWithMultipleValuesSeparatedWithComma() = runTest {
        val encodedHeaders = "name:value1,value2,value3\r\n\r\n"
        val headers = inMemoryAsyncBuffer(encodedHeaders).readHeaders()

        assertEquals(1, headers.entries().size)
        assertEquals("value1,value2,value3", headers["name"].toString())
        assertEquals(listOf("value1,value2,value3"), headers.getAll("name")?.toList())
    }

    @Test
    fun parseHeadersWithEmptyValue() = runTest {
        val encodedHeaders = "name:\r\n\r\n"
        val headers = inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        assertEquals(1, headers.entries().size)
        assertEquals("", headers["name"].toString())
        assertEquals(headers.getAll("name")?.toList(), listOf(""))
    }

    @Test
    fun parseHeadersWithMultipleEmptyValues() = runTest {
        val encodedHeaders = "name: ,,,\r\n\r\n"
        val headers = inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        assertEquals(1, headers.entries().size)
        assertEquals(",,,", headers["name"].toString())
    }

    @Test
    fun parseHeadersSpaceAfterHeaderNameShouldBeProhibited() = runTest {
        val encodedHeaders = "name :value\r\n\r\n"

        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        }
    }

    @Test
    fun parseHeadersSpacesInHeaderNameShouldBeProhibited() = runTest {
        val encodedHeaders = "name and more: value\r\n\r\n"

        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        }
    }

    @Test
    fun parseHeadersDelimitersInHeaderNameShouldBeProhibited() = runTest {
        val encodedHeaders = "name,: value\r\n\r\n"

        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        }
    }

    @Test
    fun parseHeadersEmptyHeaderNameShouldBeProhibited() = runTest {
        val encodedHeaders = ": value\r\n\r\n"

        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        }.let {
            assertTrue("Empty header names are not allowed" in it.message.orEmpty())
        }
    }

    @Test
    fun parseHeadersFoldingShouldBeProhibited() = runTest {
        val encodedHeaders = "A:\r\n folding\r\n\r\n"

        assertFailsWith<ParseException> {
            inMemoryAsyncBuffer(encodedHeaders).readHeaders()
        }
    }
}

private suspend fun inMemoryAsyncBuffer(str: String) =
    InMemoryAsyncBuffer().apply { writeString(str) }
