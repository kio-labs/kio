package kio.async.poller.epoll

import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.PollInterest
import kio.async.Poller
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
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
import platform.posix.pollfd

object EPoll : Poller.Factory {
    override fun create(): Poller = EpollPoller()
}

private const val EVENT_CAPACITY = 64

private class EpollPoller : Poller {
    val epollfd = epoll_create1(0)

    @OptIn(ExperimentalForeignApi::class)
    val arean = Arena()

    @OptIn(ExperimentalForeignApi::class)
    val events = arean.allocArray<epoll_event>(EVENT_CAPACITY)

    // map of fd and events
    val registeredEvents: MutableMap<Int, UInt> = mutableMapOf()

    init {
        if (epollfd == -1) {
            throw IOException("Exception when create epollfd.")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun attach(handle: Any, event: PollInterest) {
        val fd = handle as Int
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
    override fun detach(handle: Any, event: PollInterest) {
        val fd = handle as Int
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

    @OptIn(ExperimentalForeignApi::class)
    override fun poll(
        timeoutMillis: Long,
        onActive: (handle: Any, event: PollInterest) -> Unit
    ) {
        val nfd = epoll_wait(epollfd, events, EVENT_CAPACITY, timeoutMillis.toInt())
        if (nfd == -1) {
            throw IOException("error happened when epoll_wait")
        }

        for (i in 0 until nfd) {
            val event = events[i]
            val fd = event.data.fd
            val events = event.events

            if ((events and (EPOLLERR or EPOLLHUP or EPOLLRDHUP)) != 0U) {
                onActive(fd, POLL_INTEREST_READ)
                onActive(fd, POLL_INTEREST_WRITE)
                continue
            }
            if ((events and EPOLLOUT) != 0U) {
                onActive(fd, POLL_INTEREST_WRITE)
            }
            if ((events and EPOLLIN) != 0U) {
                onActive(fd, POLL_INTEREST_READ)
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