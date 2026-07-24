package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring
import kio.postegre.types.PgInt4
import kio.tls.withClientTls
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class EpollPgConnectionTest : NativePgConnectionTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class UringPgConnectionTest : NativePgConnectionTest() {
    override val pollerFactory: PollerFactory = LinuxUring
}
