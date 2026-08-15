package kio.postgres.migration

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EpollPgMigrationTest: PgMigrationTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class LinuxUringPgMigrationTest: PgMigrationTest() {
    override val pollerFactory: PollerFactory = LinuxUring
}
