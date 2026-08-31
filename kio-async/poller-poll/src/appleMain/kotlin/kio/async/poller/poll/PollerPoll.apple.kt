package kio.async.poller.poll

import kio.async.Poller
import kio.async.PollerFactory
import kio.async.PosixSuspendIo
import kio.async.SuspendIo

actual val PosixPoll: PollerFactory = object : PollerFactory {
    override fun create(): Poller {
        return object : NativePoller(), SuspendIo, PosixSuspendIo {
            override val io: SuspendIo = this
        }
    }
}