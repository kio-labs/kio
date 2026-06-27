package kio.postgres.conn

import kio.async.Poller
import kio.async.poller.poll.PosixPoll

class PosixPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: Poller.Factory = PosixPoll
}
