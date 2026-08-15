package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.poller.select.Select

class JvmSelectPgMigrationTest: PgMigrationTest() {
    override val pollerFactory: PollerFactory = Select
}