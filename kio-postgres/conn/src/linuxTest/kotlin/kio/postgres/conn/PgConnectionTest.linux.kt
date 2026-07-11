package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class UringPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = LinuxUring
}
