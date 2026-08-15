package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class PosixPgMigrationTest : PgMigrationTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}