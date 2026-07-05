package kio.async.poller.kqueue

import kio.async.PollInterest
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.Poller
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.EV_ADD
import platform.darwin.EV_DELETE
import platform.darwin.EV_ENABLE
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.errno
import platform.posix.strerror
import platform.posix.timespec
import kotlin.concurrent.atomics.ExperimentalAtomicApi

object Kqueue : Poller.Factory {
    override fun create(): Poller = KqueuePoller()
}

private class KqueuePoller : Poller {
    private val kq = kqueue()

    @OptIn(ExperimentalForeignApi::class)
    private val arean = Arena()

    @OptIn(ExperimentalForeignApi::class)
    private val events = arean.allocArray<kevent>(EVENT_CAPACITY)

    @OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
    override fun attach(handle: Any, event: PollInterest): Unit = memScoped {
        handle as Int

        val change = alloc<kevent> {
            ident = handle.toULong()
            filter = event.filter()
            flags = (EV_ADD or EV_ENABLE).toUShort()
            fflags = 0U
            data = 0
            udata = null
        }

        if (kevent(kq, change.ptr, 1, null, 0, null) == -1) {
            throw IOException("error when register change list: ${errnoMessage()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
    override fun detach(handle: Any, event: PollInterest): Unit = memScoped {
        handle as Int

        val change = alloc<kevent> {
            ident = handle.toULong()
            filter = event.filter()
            flags = (EV_DELETE).toUShort()
            fflags = 0U
            data = 0
            udata = null
        }

        if (kevent(kq, change.ptr, 1, null, 0, null) == -1) {
            // Ignore error when unregister.
            println("error when unregister change list: ${errnoMessage()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun poll(
        timeoutMillis: Long,
        onActive: (handle: Any, event: PollInterest) -> Unit
    ) = memScoped {
        val timeoutPtr = when {
            timeoutMillis < 0 -> null

            else -> alloc<timespec>().apply {
                tv_sec = timeoutMillis / 1000
                tv_nsec = (timeoutMillis % 1000) * 1_000_000
            }.ptr
        }

        val n = kevent(kq, null, 0, events, EVENT_CAPACITY, timeoutPtr)

        if (n < 0) {
            throw IOException("error when poll kqueue")
        }

        for (i in 0 until n) {
            val e = events[i]
            val fd = e.ident.toInt()
            val pollInterest = when (e.filter.toInt()) {
                EVFILT_READ -> POLL_INTEREST_READ
                EVFILT_WRITE -> POLL_INTEREST_WRITE
                else -> continue
            }

            onActive(fd, pollInterest)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        arean.clear()
        platform.posix.close(kq)
    }
}

private const val EVENT_CAPACITY = 1024

private fun PollInterest.filter() = when (this) {
    POLL_INTEREST_READ -> EVFILT_READ.toShort()
    POLL_INTEREST_WRITE -> EVFILT_WRITE.toShort()
    else -> error("never")
}

@OptIn(ExperimentalForeignApi::class)
internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}
