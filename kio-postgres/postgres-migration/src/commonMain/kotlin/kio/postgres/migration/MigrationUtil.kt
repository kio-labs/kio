package kio.postgres.migration

import kio.postgres.conn.PgConnection
import kio.postgres.conn.exec
import kio.postgres.conn.param
import kio.postgres.conn.query
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
}

suspend fun PgConnection.migrate(migrations: List<Migration>): MigrationResult {
    ensureMigrationTable()

    val appliedVersions = getAppliedMigrationVersions()

//    for (migration in migrations.sortedBy { it.version }) {
//        if (migration.version in appliedVersions) {
//            continue
//        }
//
//        // execute migration
//    }

    TODO()
}

private suspend fun PgConnection.ensureMigrationTable() {
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

suspend fun PgConnection.getAppliedMigrationVersions(): Map<PgInt8, MigrationEntity> {
    val ret: Flow<MigrationEntity> = query(
        """
        select * from schema_migrations
        order by version
        """.trimIndent()
    )
    val entities: MutableList<MigrationEntity> = mutableListOf()
    ret.toCollection(entities)

    return entities.associateBy { it.version }
}

suspend fun PgConnection.recordMigration(
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
        // TODO: calculate sql checksum
        param(migration.sql, PostgresTextSerializer)
    }
}
