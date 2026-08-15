package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class KqueuePgMigrationTest : PgMigrationTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}