package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueuePgMigrationTest : PgMigrationTest() {
    override val pollerFactory: PollerFactory = Kqueue
}