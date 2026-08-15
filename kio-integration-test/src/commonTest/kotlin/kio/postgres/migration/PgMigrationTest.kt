package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.runPollEventLoop
import kio.postgres.conn.PgConnection
import kio.postgres.conn.getEnv
import kio.postgres.conn.openPgConnection
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

abstract class PgMigrationTest {
    abstract val pollerFactory: PollerFactory

    @AfterTest
    fun clearTables() = withTestPgDatabase {
        exec("drop table if exists schema_migrations cascade")
    }

    @Test
    fun migrationFailedWithInvalidSql() = withTestPgDatabase {
        val result = migrate(listOf(Migration(1, "schema 1", "invalid_sql")))
        assertIs<MigrationResult.Error.ExecutionFailed>(result)
    }

    @Test
    fun migrationFailedWithSqlCheck() = withTestPgDatabase {
        migrate(listOf(Migration(1, "schema 1", "select 1"))).also {
            assertIs<MigrationResult.Success>(it)
        }
        migrate(listOf(Migration(1, "schema 1", "select 2"))).also {
            assertIs<MigrationResult.Error.AppliedMigrationMismatch>(it)
        }
    }

    @Test
    fun migrationFailedWithInvalidMigration() = withTestPgDatabase {
        migrate(listOf(
            Migration(1, "schema 1", "select 1"),
            Migration(1, "schema 2", "select 1"),
        )).also {
            assertIs<MigrationResult.Error.InvalidMigrationSet>(it)
            assertEquals("Duplicate migration version", it.reason)
        }
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
                conn.block()
                conn.close()
            }
        }
}