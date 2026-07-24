@file:OptIn(ExperimentalForeignApi::class)

package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll
import kio.postegre.types.PgInt4
import kio.tls.withClientTls
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.serialization.Serializable
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class NativePgConnectionTest: PgConnectionTest() {
    @Test
    fun connDirectTlsTest() = withTestPgDatabase(
        tlsNegotiation = TlsNegotiation.PREFER,
        tlsWrapper = { it.withClientTls(host) }
    ) {
        exec("create temporary table foo(id integer primary key)")

        // Accept parameters
        @Serializable
        class Foo(val a: PgInt4)
        assertEquals(
            "INSERT 0 1",
            exec("insert into foo(id) values($1)", Foo(1))
        )
    }
}

class PosixPgConnectionTest : NativePgConnectionTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}

actual fun getEnv(key: String): String? {
    return getenv(key)?.toKString()
}