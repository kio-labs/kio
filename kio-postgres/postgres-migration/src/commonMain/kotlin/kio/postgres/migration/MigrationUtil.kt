package kio.postgres.migration

import kio.postgres.conn.PgConnection
import kio.postgres.conn.TransactionScope
import kio.postgres.conn.param
import kio.postgres.conn.query
import kio.postgres.conn.transaction
import kio.postgres.types.PgInt8
import kio.postgres.types.PgText
import kio.postgres.types.PgTimestampTz
import kio.postgres.types.PostgresInt8Serializer
import kio.postgres.types.PostgresTextSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toCollection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kotlincrypto.hash.sha2.SHA256

data class Migration(
    val version: Long,
    val name: String,
    val sql: String,
)

sealed interface MigrationResult {
    data object Success : MigrationResult
    sealed interface Error : MigrationResult {
        data class InvalidMigrationSet(val reason: String) : Error
        data class AppliedMigrationMismatch(val migration: Migration) : Error
        data class ExecutionFailed(val migration: Migration, val cause: Throwable) : Error
    }
}

suspend fun PgConnection.migrate(
    migrations: List<Migration>,
): MigrationResult {
    ensureMigrationHistoryTable()

    val sortedMigrations = migrations.sortedBy { it.version }

    if (sortedMigrations.map { it.version }.distinct().size != sortedMigrations.size) {
        return MigrationResult.Error.InvalidMigrationSet(reason = "Duplicate migration version")
    }

    val appliedMigrations = loadAppliedMigrations()

    for (migration in sortedMigrations) {
        val appliedMigration = appliedMigrations[migration.version]
        if (appliedMigration != null) {
            if (appliedMigration.checksum != calculateMigrationChecksum(migration.sql)) {
                return MigrationResult.Error.AppliedMigrationMismatch(migration = migration)
            }

            continue
        }

        val result = runCatching {
            transaction { tx ->
                tx.exec(migration.sql)
                tx.recordAppliedMigration(migration)
            }
        }

        if (result.isFailure) {
            return MigrationResult.Error.ExecutionFailed(
                migration = migration,
                cause = result.exceptionOrNull()!!,
            )
        }
    }

    return MigrationResult.Success
}

private suspend fun PgConnection.ensureMigrationHistoryTable() {
    exec(
        """
        create table if not exists schema_migrations (
            version bigint primary key,
            name text not null,
            checksum text not null,
            applied_at timestamptz not null default now()
        )
        """.trimIndent()
    )
}

@Serializable
private data class AppliedMigrationEntity(
    @SerialName("version")
    val version: PgInt8,
    @SerialName("name")
    val name: PgText,
    @SerialName("checksum")
    val checksum: PgText,
    @SerialName("applied_at")
    val appliedAt: PgTimestampTz,
)

private suspend fun PgConnection.loadAppliedMigrations(): Map<PgInt8, AppliedMigrationEntity> {
    val rows: Flow<AppliedMigrationEntity> = query(
        """
        select *
        from schema_migrations
        order by version
        """.trimIndent()
    )

    val entities = mutableListOf<AppliedMigrationEntity>()
    rows.toCollection(entities)

    return entities.associateBy { it.version }
}

private suspend fun TransactionScope.recordAppliedMigration(
    migration: Migration,
) {
    exec(
        """
        insert into schema_migrations (
            version,
            name,
            checksum
        )
        values ($1, $2, $3)
        """.trimIndent()
    ) {
        param(migration.version, PostgresInt8Serializer)
        param(migration.name, PostgresTextSerializer)
        param(calculateMigrationChecksum(migration.sql), PostgresTextSerializer)
    }
}

private fun calculateMigrationChecksum(sql: String): String {
    return sha256(sql.encodeToByteArray()).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

private fun sha256(data: ByteArray): ByteArray {
    val sha256 = SHA256()
    sha256.update(data)
    return sha256.digest()
}
