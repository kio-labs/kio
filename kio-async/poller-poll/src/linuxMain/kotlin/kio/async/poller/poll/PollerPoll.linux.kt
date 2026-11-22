package kio.async.poller.poll

import kio.async.FdRegistration
import kio.async.Poller
import kio.async.PollerFactory
import kio.async.PosixApi
import kio.async.SuspendIo

actual val PosixPoll: PollerFactory = object : PollerFactory {
    override fun create(): Poller {
        return object : NativePoller(), SuspendIo {
            override val io: SuspendIo = PollerIo(this).let { io ->
                object : SuspendIo, PosixApi by io, FdRegistration by io {}
            }
        }
    }
}
