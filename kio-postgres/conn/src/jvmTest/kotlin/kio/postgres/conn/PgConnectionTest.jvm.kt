package kio.postgres.conn

import kio.async.Poller
import kio.async.poller.select.Select

class PosixPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: Poller.Factory = Select
}
