package kio.async.poller.poll

import kio.async.LinuxSuspendIo
import kio.async.Poller
import kio.async.PollerFactory
import kio.async.SuspendIo
import kio.async.PosixSuspendIo

actual val PosixPoll: PollerFactory = object : PollerFactory {
    override fun create(): Poller {
        return object : NativePoller(), SuspendIo, PosixSuspendIo, LinuxSuspendIo {
            override val io: SuspendIo = this
        }
    }
}