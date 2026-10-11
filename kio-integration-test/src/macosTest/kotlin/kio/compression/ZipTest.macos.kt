package kio.compression

import kio.async.PollerFactory
import kio.async.poller.kqueue.Kqueue

class KqueueZipTest : ZipTest() {
    override val pollerFactory: PollerFactory = Kqueue
}