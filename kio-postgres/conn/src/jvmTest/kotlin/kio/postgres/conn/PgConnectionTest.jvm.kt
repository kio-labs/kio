package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.select.Select

class JvmSelectPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = Select
}

actual fun getEnv(key: String): String? {
    return System.getenv(key)
}