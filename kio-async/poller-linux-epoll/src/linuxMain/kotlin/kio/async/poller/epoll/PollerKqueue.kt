package kio.async.poller.epoll

import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.PollInterest
import kio.async.Poller
import kio.async.PollerFactory
import kio.async.polling.PollingSuspendIo
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLLRDHUP
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import kotlin.collections.set
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

object EPoll : PollerFactory {
    override fun create(): Poller = EpollPoller()
}

private const val EVENT_CAPACITY = 64

private class EpollPoller : Poller, PollingSuspendIo {
    val epollfd = epoll_create1(0)

    @OptIn(ExperimentalForeignApi::class)
    val arean = Arena()

    @OptIn(ExperimentalForeignApi::class)
    val events = arean.allocArray<epoll_event>(EVENT_CAPACITY)

    // map of fd and events
    val registeredEvents: MutableMap<Int, UInt> = mutableMapOf()

    private val continuationMap: MutableMap<Pair<Int, PollInterest>, Continuation<Unit>> =
        mutableMapOf()

    init {
        if (epollfd == -1) {
            throw IOException("Exception when create epollfd.")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun attach(fd: Int, event: PollInterest) {
        val oldEvents = registeredEvents[fd] ?: 0U
        val newEvents = oldEvents or event.toEvent()

        if (oldEvents == newEvents) return

        val ev = cValue<epoll_event> {
            this.events = newEvents
            this.data.fd = fd
        }
        val result = if (oldEvents == 0U) {
            epoll_ctl(epollfd, EPOLL_CTL_ADD, fd, ev)
        } else {
            epoll_ctl(epollfd, EPOLL_CTL_MOD, fd, ev)
        }
        if (result == -1) {
            throw IOException("error happened when attach: fd=$fd, interest=$event")
        }
        registeredEvents[fd] = newEvents
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun detach(fd: Int, event: PollInterest) {
        val oldEvents = registeredEvents[fd] ?: return

        val newEvents = oldEvents and event.toEvent().inv()

        val result = if (newEvents == 0U) {
            epoll_ctl(epollfd, EPOLL_CTL_DEL, fd, null)
            registeredEvents.remove(fd)
        } else {
            val ev = cValue<epoll_event> {
                this.events = newEvents
                this.data.fd = fd
            }
            epoll_ctl(epollfd, EPOLL_CTL_MOD, fd, ev)
            registeredEvents[fd] = newEvents
        }
        if (result == -1) {
            throw IOException("error happened when detach: fd=$fd, interest=$event")
        }
    }

    override suspend fun awaitIo(handle: Int, interest: PollInterest) = suspendCancellableCoroutine { c ->
        continuationMap[handle to interest] = c
        c.invokeOnCancellation {
            continuationMap.remove(handle to interest)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun poll(timeoutMillis: Long) {
        val nfd = epoll_wait(epollfd, events, EVENT_CAPACITY, timeoutMillis.toInt())
        if (nfd == -1) {
            throw IOException("error happened when epoll_wait")
        }

        for (i in 0 until nfd) {
            val event = events[i]
            val fd = event.data.fd
            val events = event.events

            if ((events and (EPOLLERR or EPOLLHUP or EPOLLRDHUP)) != 0U) {
                continuationMap.remove(fd to POLL_INTEREST_READ)?.resume(Unit)
                continuationMap.remove(fd to POLL_INTEREST_WRITE)?.resume(Unit)
                continue
            }
            if ((events and EPOLLOUT) != 0U) {
                val c = continuationMap.remove(fd to POLL_INTEREST_WRITE)
                c?.resume(Unit)
            }
            if ((events and EPOLLIN) != 0U) {
                val c = continuationMap.remove(fd to POLL_INTEREST_READ)
                c?.resume(Unit)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        platform.posix.close(epollfd)
        arean.clear()
    }
}

private fun PollInterest.toEvent(): UInt {
    return when (this) {
        POLL_INTEREST_READ -> EPOLLIN
        POLL_INTEREST_WRITE -> EPOLLOUT
        else -> error("invalid PollInterest $this")
    }
}