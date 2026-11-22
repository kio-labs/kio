package kio.async.poller.epoll

import kio.async.*
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import platform.linux.*
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.strerror
import kotlin.coroutines.resume

object EPoll : PollerFactory {
    override fun create(): Poller = EpollPoller()
}

val SuspendIo.epoll: EpollRegister
    get() = (this as EpollIo).register

interface EpollRegister {
    fun attach(fd: Int, interest: UInt) {}
    fun detach(fd: Int, interest: UInt) {}

    suspend fun awaitIo(fd: Int, interest: UInt)
}

private const val EVENT_CAPACITY = 64

private class EpollPoller : Poller, EpollRegister {
    val epollfd = epoll_create1(0)

    @OptIn(ExperimentalForeignApi::class)
    val arean = Arena()

    @OptIn(ExperimentalForeignApi::class)
    val events = arean.allocArray<epoll_event>(EVENT_CAPACITY)

    // map of fd and events
    val registeredEvents: MutableMap<Int, UInt> = mutableMapOf()

    private data class Task(
        val interest: UInt,
        val task: CancellableContinuation<Unit>
    )

    private val continuationMap: MutableMap<Int, MutableList<Task>> = mutableMapOf()

    init {
        if (epollfd == -1) {
            throw IOException("Exception when create epollfd.")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun attach(fd: Int, interest: UInt) {
        val oldEvents = registeredEvents[fd] ?: 0U
        val newEvents = oldEvents or interest

        if (oldEvents == newEvents) return

        val ev = cValue<epoll_event> {
            this.events = newEvents or EPOLLET
            this.data.fd = fd
        }
        val result = if (oldEvents == 0U) {
            epoll_ctl(epollfd, EPOLL_CTL_ADD, fd, ev)
        } else {
            epoll_ctl(epollfd, EPOLL_CTL_MOD, fd, ev)
        }
        if (result == -1) {
            throw IOException("error happened when attach: fd=$fd, interest=$interest")
        }
        registeredEvents[fd] = newEvents
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun detach(fd: Int, interest: UInt) {
        val oldEvents = registeredEvents[fd] ?: return

        val newEvents = oldEvents and interest.inv()

        val result = if (newEvents == 0U) {
            epoll_ctl(epollfd, EPOLL_CTL_DEL, fd, null)
            registeredEvents.remove(fd)
        } else {
            val ev = cValue<epoll_event> {
                this.events = newEvents or EPOLLET
                this.data.fd = fd
            }
            epoll_ctl(epollfd, EPOLL_CTL_MOD, fd, ev)
            registeredEvents[fd] = newEvents
        }
        if (result == -1) {
            throw IOException("error happened when detach: fd=$fd, interest=$interest")
        }
    }

    override suspend fun awaitIo(fd: Int, interest: UInt) = suspendCancellableCoroutine { c ->
        val tasks = continuationMap.getOrPut(fd) { mutableListOf() }
        val task = Task(interest, c)
        tasks.add(task)
        c.invokeOnCancellation {
            continuationMap[fd]?.remove(task)
        }
    }

    override val io: SuspendIo = EpollIo(this)

    @OptIn(ExperimentalForeignApi::class)
    override fun poll(timeoutMillis: Long) {
        require(timeoutMillis >= -1L) { "Invalid poll timeout: $timeoutMillis" }
        val timeout = if (timeoutMillis == -1L) -1 else timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nfd = epoll_wait(epollfd, events, EVENT_CAPACITY, timeout)
        if (nfd == -1) {
            val error = errno
            // Let the dispatcher run timers and recompute the remaining wait timeout.
            if (error == EINTR) return
            throw IOException("epoll_wait failed: epollfd=$epollfd, timeout=$timeout, " + "errno=$error (${strerror(error)?.toKString()})")
        }

        for (i in 0 until nfd) {
            val event = events[i]
            val fd = event.data.fd
            val events = event.events

            if ((events and (EPOLLERR or EPOLLHUP)) != 0U) {
                val tasks = continuationMap[fd] ?: continue
                tasks.forEach { (_, c) -> c.resume(Unit)}
                tasks.clear()
                continue
            }

            val i = continuationMap[fd]?.iterator() ?: continue
            while (i.hasNext()) {
                val (interest, c) = i.next()
                if (interest and events != 0U) {
                    i.remove()
                    c.resume(Unit)
                }
            }
        }
    }
    override fun shutdown() {
        val continuations = continuationMap.values.toList()
        continuationMap.clear()

        val cause = CancellationException("Epoll poller shutdown")

        continuations.forEach { tasks ->
            tasks.forEach { (_, c) ->
                c.cancel(cause)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        platform.posix.close(epollfd)
        arean.clear()
    }
}

private class EpollIo(val register: EpollRegister): SuspendIo, PosixSuspendIo(), FdRegistration {
    override suspend fun awaitForRead(fd: Int) {
        register.awaitIo(fd, EPOLLIN)
    }

    override suspend fun awaitForWrite(fd: Int) {
        register.awaitIo(fd, EPOLLOUT)
    }

    override fun attachRead(fd: Int) {
        register.attach(fd, EPOLLIN)
    }

    override fun attachWrite(fd: Int) {
        register.attach(fd, EPOLLOUT)
    }

    override fun detachRead(fd: Int) {
        register.detach(fd, EPOLLIN)
    }

    override fun detachWrite(fd: Int) {
        register.detach(fd, EPOLLOUT)
    }
}