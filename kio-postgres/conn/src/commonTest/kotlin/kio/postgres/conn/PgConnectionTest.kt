package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.io.buffered
import kio.async.io.openPipe
import kio.async.runPollEventLoop
import kio.async.writeString
import kio.postegre.types.PgBool
import kio.postegre.types.PgInt4
import kio.postegre.types.PgText
import kio.postegre.types.PgTimestamp
import kio.postegre.types.PostgresFormat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

abstract class PgConnectionTest {
    abstract val pollerFactory: PollerFactory

    @Test
    fun execTest() = withTestPgDatabase {
        exec("create temporary table foo(id integer primary key)")

        // Accept parameters
        @Serializable
        class Foo(val a: PgInt4)
        assertEquals(
            "INSERT 0 1",
            exec("insert into foo(id) values($1)", Foo(1))
        )
        assertEquals(
            "DROP TABLE",
            exec("drop table foo")
        )
        // Multiple statements can be executed -- last command tag is returned
        assertEquals(
            "DROP TABLE",
            exec("create temporary table foo(id serial primary key); drop table foo;")
        )
        // Exec no-op which does not return a command tag
        assertEquals(
            "",
            exec("--;")
        )
    }

    @Test
    fun execFailureTest() = withTestPgDatabase {
        assertFails {
            exec("selct;")
        }
    }

    @Test
    fun execFailureWithArgumentTest() = withTestPgDatabase {
        @Serializable
        class Foo(val a: PgInt4)
        assertFails { exec("selct $1;", Foo(1)) }
    }

    @Test
    fun insertBooleanArrayTest() = withTestPgDatabase {
        @Serializable
        class Foo(val a: List<PgBool>)
        exec("create temporary table foo(spice bool[])")
        assertEquals(
            "INSERT 0 1",
            exec("insert into foo(spice) values($1)", Foo(listOf(true, false, true)))
        )
    }

    @Test
    fun insertTimestampArrayTest() = withTestPgDatabase {
        @Serializable
        class Foo(val a: List<PgTimestamp>)
        exec("create temporary table foo(spice timestamp[]);")
        assertEquals(
            "INSERT 0 1",
            exec(
                "insert into foo(spice) values($1)", Foo(
                    listOf(
                        LocalDateTime(2014, 12, 21, 6, 34, 27),
                        LocalDateTime(2014, 12, 21, 6, 34, 32),
                    )
                )
            )
        )
    }

    @Test
    fun pipelineQueryErrorBetweenSyncsTest() = withTestPgDatabase {
        var isCalledAfterFailed = false
        val result = runCatching {
            pipeline {
                withSync {
                    query("select 1")
                    query("select 2")
                }
                withSync {
                    query("select 3")
                    query("select 1/(3-n) from generate_series(1,10) n")
                    query("select 4")
                }
                withSync {
                    query("select 5")
                    query("select 6")
                }

                @Serializable
                data class Result(val a: PgInt4)
                consumeSync {
                    assertEquals(listOf(Result(1)), receiveAsList<Result>())
                    assertEquals(listOf(Result(2)), receiveAsList<Result>())
                }
                consumeSync {
                    assertEquals(listOf(Result(3)), receiveAsList<Result>())
                    assertEquals(
                        true,
                        receiveAsList<Result>().isEmpty()
                    ) // failed by divide by zero but not throw exception
                    error("never") // never exec because last step failed.
                }
                consumeSync {
                    assertEquals(listOf(Result(5)), receiveAsList<Result>())
                    assertEquals(listOf(Result(6)), receiveAsList<Result>())
                    isCalledAfterFailed = true
                }
            }
        }
        assertTrue(isCalledAfterFailed)
        assertFails { result.getOrThrow() }
    }

    @Test
    fun pipelineGetResultsHandlesPartiallyReadResultsTest() = withTestPgDatabase {
        pipeline {
            @Serializable
            class Input(val a: PgInt4, val b: PgInt4)
            withSync {
                query("select n from generate_series($1::int, $2::int) n", Input(1, 3))
                query("select n from generate_series($1::int, $2::int) n", Input(5, 7))
            }

            consumeSync {
                receive()
                // second query not read.
            }
        }
    }

    @Test
    fun connCopyToSmallTest() = withTestPgDatabase {
        exec(
            """
            create temporary table foo(
                a int2,
                b int4,
                c int8,
                d varchar,
                e text,
                f date,
                g json
            )
        """.trimIndent()
        )
        exec("insert into foo values (0, 1, 2, 'abc', 'efg', '2000-01-01', '{\"abc\":\"def\",\"foo\":\"bar\"}')")
        exec("insert into foo values (null, null, null, null, null, null, null)")

        val buffer = Buffer()
        assertEquals("COPY 2", copyTo("copy foo to stdout", buffer))
        val expect = "0\t1\t2\tabc\tefg\t2000-01-01\t{\"abc\":\"def\",\"foo\":\"bar\"}\n" +
                "\\N\t\\N\t\\N\t\\N\t\\N\t\\N\t\\N\n"

        assertEquals(expect, buffer.readString())
    }

    @Test
    fun connCopyToLargeTest() = withTestPgDatabase {
        exec(
            """
            create temporary table foo(
                a int2,
                b int4,
                c int8,
                d varchar,
                e text,
                f date,
                g json,
                h bytea
            )
            """.trimIndent()
        )

        val expect = Buffer()
        repeat(1000) {
            exec("insert into foo values (0, 1, 2, 'abc', 'efg', '2000-01-01', '{\"abc\":\"def\",\"foo\":\"bar\"}', 'oooo')")
            expect.writeString("0\t1\t2\tabc\tefg\t2000-01-01\t{\"abc\":\"def\",\"foo\":\"bar\"}\t\\\\x6f6f6f6f\n")
        }

        val buffer = Buffer()
        assertEquals("COPY 1000", copyTo("copy foo to stdout", buffer))
        assertEquals(expect.readString(), buffer.readString())
    }

    @Test
    fun connCopyToQueryErrorTest() = withTestPgDatabase {
        val buffer = Buffer()
        assertFails { copyTo("cropy foo to stdout", buffer) }
    }

    @Test
    fun connCopyToCanceledTest() = withTestPgDatabase {
        // TODO
    }

    @Test
    fun connCopyFromTest() = withTestPgDatabase {
        exec(
            """
            create temporary table foo(
                a int4,
                b varchar
            )
        """.trimIndent()
        )
        val srcBuf = Buffer()

        @Serializable
        data class Foo(val a: PgInt4, val b: PgText)

        val srcList = mutableListOf<Foo>()
        repeat(1000) { i ->
            val b = "foo $i bar"
            srcList.add(Foo(i, b))
            srcBuf.writeString("$i,${b}\n")
        }

        assertEquals("COPY 1000", copyFrom("COPY foo FROM STDIN WITH (FORMAT csv)", srcBuf))

        val resultList = mutableListOf<Foo>()
        query<Foo>("select * from foo").toCollection(resultList)
        assertEquals(srcList, resultList)
    }

    @Test
    fun connCopyFromBinaryTest() = withTestPgDatabase {
        exec(
            """
            create temporary table foo(
                a int4,
                b varchar
            )
        """.trimIndent()
        )

        val srcBuf = Buffer()
        srcBuf.writeString("PGCOPY\n")
        srcBuf.writeByte(0xFF.toByte())
        srcBuf.writeString("\r\n")
        srcBuf.writeByte(0x00)
        srcBuf.writeInt(0)
        srcBuf.writeInt(0)

        @Serializable
        data class Foo(val a: PgInt4, val b: PgText)

        val srcList = mutableListOf<Foo>()
        repeat(1000) { i ->
            val b = "foo $i bar"
            val foo = Foo(i, b)
            srcList.add(foo)
            srcBuf.write(PostgresFormat.encodeToByteArray(foo))
        }

        assertEquals("COPY 1000", copyFrom("COPY foo (a, b) FROM STDIN BINARY;", srcBuf))

        val resultList = mutableListOf<Foo>()
        query<Foo>("select * from foo").toCollection(resultList)
        assertEquals(srcList, resultList)
    }

    @Test
    fun connCopyFromCanceledTest() {
        val elapsed = measureTime {
            withTestPgDatabase {
                coroutineScope {
                    exec(
                        """
                        create temporary table foo(
                            a int4,
                            b varchar
                        )
                        """.trimIndent()
                    )
                    val pipe = openPipe().buffered()
                    val source = pipe.source
                    val sink = pipe.sink
                    val sendJob = launch {
                        try {
                            repeat(1_000_000) { i ->
                                val b = "foo $i bar"
                                sink.writeString("$i,${b}\n")
                                sink.flush()
                                delay(1.milliseconds)
                            }
                        } finally {
                            sink.flush()
                            pipe.close()
                        }
                    }

                    val copyJob = launch {
                        println(copyFrom("COPY foo FROM STDIN WITH (FORMAT csv)", source))
                        pipe.close()
                    }

                    val delayTask = launch {
                        delay(0.2.seconds)
                        copyJob.cancel()
                        sendJob.cancel()
                    }
                    joinAll(sendJob, copyJob, delayTask)
                    pipe.close()
                }
            }
        }
        assertTrue(elapsed < 5.seconds)
    }

    @Test
    fun connExecParamsCanceledTest() {
        val elapsed = measureTime {
            withTestPgDatabase {
                coroutineScope {
                    val job = launch { exec("select current_database(), pg_sleep(10)") }
                    val delayJob = launch {
                        delay(0.1.seconds)
                        job.cancel()
                    }
                    joinAll(job, delayJob)
                }
            }
        }

        assertTrue(elapsed < 5.seconds)
    }

    @Test
    fun connExecParamsCanceledAndCheckStatusTest() {
        val elapsed = measureTime {
            withTestPgDatabase {
                coroutineScope {
                    val job = launch { exec("select current_database(), pg_sleep(10)") }
                    val delayJob = launch {
                        delay(0.1.seconds)
                        job.cancel()
                    }
                    joinAll(job, delayJob)
                    exec("select 1")
                }
            }
        }

        assertTrue(elapsed < 5.seconds)
    }

    @Test
    fun connWaitForNotificationTest() {
        withTestPgDatabase {
            coroutineScope {
                exec("listen foo")

                launch {
                    assertEquals("bar",waitNotification().message)
                }
                launch {
                    assertEquals("bar",waitNotification().message)
                }

                exec("notify foo, 'bar'")
            }
            exec("select 1")
        }
    }



    private fun withTestPgDatabase(block: suspend PgConnection.() -> Unit) =
        runPollEventLoop(pollerFactory) {
            val HOST_IP = "localhost"
            val PORT = 5432
            withTimeout(1.seconds) {
                val conn = openPgConnection(HOST_IP, PORT, "test_clear", database = "postgres", password = "test123")
                conn.block()
                conn.close()
            }
        }
}