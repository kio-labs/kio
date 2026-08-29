package kio.async.poller.poll

import kio.async.Poller
import kio.async.PollerFactory
import kio.async.SuspendIo
import kio.async.PollBasedSuspendIo

actual val PosixPoll: PollerFactory = object : PollerFactory {
    override fun create(): Poller {
        return object : NativePoller(), SuspendIo, PollBasedSuspendIo {
            override val io: SuspendIo = this
        }
    }
}