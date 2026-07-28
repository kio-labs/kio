@file:OptIn(ExperimentalForeignApi::class)

package kio.postgres.conn

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

class PosixPgConnectionTest : PgConnectionTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}

actual fun getEnv(key: String): String? {
    return getenv(key)?.toKString()
}