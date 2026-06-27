package kio.postgres.conn

import kio.async.Poller
import kio.async.poller.kqueue.Kqueue

class KqueuePgConnectionTest : PgConnectionTest() {
    override val pollerFactory: Poller.Factory = Kqueue
}
