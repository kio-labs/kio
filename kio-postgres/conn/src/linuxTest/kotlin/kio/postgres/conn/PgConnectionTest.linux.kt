package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll

class EpollPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = EPoll
}
