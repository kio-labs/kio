package kio.compression

import kio.async.PollerFactory
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring

class EPollZipTest : ZipTest() {
    override val pollerFactory: PollerFactory = EPoll
}

class UringZipTest : ZipTest() {
    override val pollerFactory: PollerFactory = LinuxUring()
}
