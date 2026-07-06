package kio.postgres.conn

import kio.async.Poller
import kio.async.poller.epoll.EPoll

class EpollPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: Poller.Factory = EPoll
}
