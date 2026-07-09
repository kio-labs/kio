package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueuePgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = Kqueue
}
