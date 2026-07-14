package kio.tls

import kio.async.PollerFactory
import kio.async.poller.poll.PosixPoll

class PosixPollTlsConnectionTest : TlsConnectionTest() {
    override val pollerFactory: PollerFactory = PosixPoll
}
