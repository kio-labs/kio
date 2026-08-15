package kio.postgres.migration

import kio.postgres.conn.PgConnection
import kio.postgres.conn.TransactionScope
import kio.postgres.conn.exec
import kio.postgres.conn.param
import kio.postgres.conn.query
import kio.postgres.conn.transaction
import kio.postgres.types.PgInt8
import kio.postgres.types.PgText
import kio.postgres.types.PgTimestampTz
import kio.postgres.types.PostgresInt8Serializer
import kio.postgres.types.PostgresTextSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.toCollection

data class Migration(
    val version: Long,
    val name: String,
    val sql: String,
)

sealed interface MigrationResult {
    data object Success: MigrationResult
    sealed interface Error: MigrationResult {
        data class MigrationInvalid(val reason: String): Error
        data class SchemaInvalid(val invalidSchema: Migration): Error
        data class MigrationFailed(val failed: Migration, val t: Throwable): Error
    }
}

suspend fun PgConnection.migrate(migrations: List<Migration>): MigrationResult {
    ensureMigrationTable()

    val sortedMigrations = migrations.sortedBy { it.version }
    if (sortedMigrations.map { it.version }.distinct().size != sortedMigrations.size) {
        return MigrationResult.Error.MigrationInvalid("duplicated schema version")
    }

    val appliedVersions = getAppliedMigrationVersions()

    for (migration in sortedMigrations) {
        val appliedVersion = appliedVersions[migration.version]

        if (appliedVersion != null) {
            if (appliedVersion.checksum != migration.sql) {
                // TODO: calculate sql checksum
                return MigrationResult.Error.SchemaInvalid(migration)
            }

            continue
        }

//        // execute migration
        val result = runCatching {
            transaction { tx ->
                tx.exec(migration.sql)
                tx.recordMigration(migration)
            }
        }

        if (result.isFailure) {
            return MigrationResult.Error.MigrationFailed(migration, result.exceptionOrNull()!!)
        }
    }

    return MigrationResult.Success
}

private suspend fun PgConnection.ensureMigrationTable() {
    exec(
        """
        create table if not exists schema_migration (
            version bigint primary key,
            name text not null,
            checksum text not null,
            applied_at timestamptz not null default now()
        )
        """.trimIndent()
    )
}

@Serializable
data class MigrationEntity(
    @SerialName("version")
    val version: PgInt8,
    @SerialName("name")
    val name: PgText,
    @SerialName("checksum")
    val checksum: PgText,
    @SerialName("applied_at")
    val appliedAt: PgTimestampTz,
)

private suspend fun PgConnection.getAppliedMigrationVersions(): Map<PgInt8, MigrationEntity> {
    val ret: Flow<MigrationEntity> = query(
        """
        select * from schema_migration
        order by version
        """.trimIndent()
    )
    val entities: MutableList<MigrationEntity> = mutableListOf()
    ret.toCollection(entities)

    return entities.associateBy { it.version }
}

private suspend fun TransactionScope.recordMigration(
    migration: Migration,
) {
    exec(
        """
        insert into schema_migration (
            version,
            name,
            checksum
        )
        values ($1, $2, $3)
        """.trimIndent()
    ) {
        param(migration.version, PostgresInt8Serializer)
        param(migration.name, PostgresTextSerializer)
        // TODO: calculate sql checksum
        param(migration.sql, PostgresTextSerializer)
    }
}
