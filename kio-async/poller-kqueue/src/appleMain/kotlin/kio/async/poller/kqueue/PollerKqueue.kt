package kio.async.poller.kqueue

import kio.async.Poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.IOException
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.EV_ADD
import platform.darwin.EV_DELETE
import platform.darwin.EV_ENABLE
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.timespec
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

object Kqueue : Poller.Factory {
    override fun create(): Poller = NativePoller()
}

private class NativePoller : Poller {
    private val kq = kqueue()

    @OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
    override fun registerFd(fd: Int, event: Poller.EventType): Unit = memScoped {
        val change = alloc<kevent> {
            ident = fd.toULong()
            filter = event.filter()
            flags = (EV_ADD or EV_ENABLE).toUShort()
            fflags = 0U
            data = 0
            udata = null
        }

        if (kevent(kq, change.ptr, 1, null, 0, null) == -1) {
            throw IOException("error when register change list")
        }
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
    override fun unRegisterFd(fd: Int, event: Poller.EventType): Unit = memScoped {
        val change = alloc<kevent> {
            ident = fd.toULong()
            filter = event.filter()
            flags = (EV_DELETE).toUShort()
            fflags = 0U
            data = 0
            udata = null
        }

        if (kevent(kq, change.ptr, 1, null, 0, null) == -1) {
            throw IOException("error when unregister change list")
        }
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
    override fun poll(timeoutMillis: Long, block: Poller.PollScope.() -> Unit) = memScoped {
        val timeoutPtr = when {
            timeoutMillis < 0 -> null

            else -> alloc<timespec>().apply {
                tv_sec = timeoutMillis / 1000
                tv_nsec = (timeoutMillis % 1000) * 1_000_000
            }.ptr
        }

        val events = allocArray<kevent>(EVENT_CAPACITY)
        val n = kevent(kq, null, 0, events, EVENT_CAPACITY, timeoutPtr)

        if (n < 0) {
            throw IOException("error when poll kqueue")
        }


        val awakeSet = buildSet {
            for (i in 0 until n) {
                val e = events[i]
                val fd = e.ident.toInt()
                val eventType = when (e.filter.toInt()) {
                    EVFILT_READ -> Poller.EventType.READ
                    EVFILT_WRITE -> Poller.EventType.WRITE
                    else -> continue
                }

                add(fd to eventType)
            }
        }

        val scope = Poller.PollScope { fd, event ->
            awakeSet.contains(fd to event)
        }
        scope.block()
    }

    override fun close() {
        platform.posix.close(kq)
    }
}

private const val EVENT_CAPACITY = 1024

private fun Poller.EventType.filter() = when (this) {
    Poller.EventType.READ -> EVFILT_READ.toShort()
    Poller.EventType.WRITE -> EVFILT_WRITE.toShort()
}
