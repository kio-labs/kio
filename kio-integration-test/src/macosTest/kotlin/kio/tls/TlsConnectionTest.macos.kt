package kio.tls

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueueTlsConnectionTest: TlsConnectionTest() {
    override val pollerFactory: PollerFactory = Kqueue
}