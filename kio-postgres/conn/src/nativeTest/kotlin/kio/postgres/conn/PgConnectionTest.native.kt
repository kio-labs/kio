package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class PosixPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}
