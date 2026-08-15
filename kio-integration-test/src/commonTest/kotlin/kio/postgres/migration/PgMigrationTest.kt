package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.runPollEventLoop
import kio.postgres.conn.PgConnection
import kio.postgres.conn.getEnv
import kio.postgres.conn.openPgConnection
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

abstract class PgMigrationTest {
    abstract val pollerFactory: PollerFactory

    @Test
    fun setGetMigrationEntity() = withTestPgDatabase {
        migrate(listOf(Migration(1, "asf", "af")))
    }

    private fun withTestPgDatabase(
        block: suspend PgConnection.() -> Unit
    ) =
        runPollEventLoop(pollerFactory) {
            val host = getEnv("POSTGRES_HOST") ?: "127.0.0.1"
            val user = getEnv("POSTGRES_USER") ?: error("no value found: POSTGRES_USER")
            val password = getEnv("POSTGRES_PASSWORD") ?: error("no value found: POSTGRES_PASSWORD")
            val database = getEnv("POSTGRES_DB") ?: error("no value found: POSTGRES_DB")
            val port = getEnv("POSTGRES_PORT")?.toInt() ?: 5432

            withTimeout(1.seconds) {
                val conn = openPgConnection(
                    host = host,
                    port = port,
                    user = user,
                    password = password,
                    database = database,
                )
                conn.exec("begin")
                try {
                    conn.block()
                } finally {
                    conn.exec("rollback")
                }
                conn.close()
            }
        }
}