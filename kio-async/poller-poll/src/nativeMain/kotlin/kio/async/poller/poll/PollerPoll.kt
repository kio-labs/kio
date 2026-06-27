package kio.async.poller.poll

import kio.async.PollInterest
import kio.async.PollInterestRead
import kio.async.PollInterestWrite
import kio.async.Poller
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import platform.posix.POLLRDNORM
import platform.posix.POLLWRNORM
import platform.posix.errno
import platform.posix.pollfd
import platform.posix.strerror
import kotlin.native.concurrent.ObsoleteWorkersApi

object PosixPoll : Poller.Factory {
    override fun create(): Poller = NativePoller()
}

private class NativePoller : Poller {
    private val pollFdRequestMap: MutableMap<Pair<Int, PollInterest>, PollFdRequest> =
        mutableMapOf()

    private val scope = fun(fd: Int, event: PollInterest): Boolean {
        val request = pollFdRequestMap[fd to event] ?: return false
        return request.needContinue()
    }

    override fun register(handle: Int, event: PollInterest) {
        val fdRequest = when (event) {
            PollInterestRead -> PollFdRequest(handle, POLLRDNORM)
            PollInterestWrite -> PollFdRequest(handle, POLLWRNORM)
            else -> error("never")
        }

        if (pollFdRequestMap.values.contains(fdRequest)) throw IllegalStateException("$handle already sleep.")
        pollFdRequestMap[handle to event] = fdRequest
    }

    override fun unRegister(handle: Int, event: PollInterest) {
        pollFdRequestMap.remove(handle to event)
    }

    override fun poll(
        timeoutMillis: Long,
        block: Poller.PollScope.() -> Unit
    ) {
        nativePoll(pollFdRequestMap.values.toList(), timeoutMillis)
        block(scope)
    }

    override fun close() = Unit

    @OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
    private fun nativePoll(fds: List<PollFdRequest>, timeoutMillis: Long): Unit = memScoped {
// TODO: avoid alloc memory in each poll
        val nativePollfd = allocArray<pollfd>(fds.size) { i ->
            fd = fds[i].fd
            events = fds[i].events.convert()
        }

        val res = platform.posix.poll(nativePollfd, fds.size.convert(), timeoutMillis.toInt())

        if (res < 0) {
            val e = errno
            val msg = strerror(e)?.toKString()
            throw IOException("poll failed: errno=$e, message=$msg")
        }

        fds.onEachIndexed { i, request ->
            request.revents = nativePollfd[i].revents.toInt()
        }
    }

    private class PollFdRequest(
        val fd: Int,
        val events: Int,
        var revents: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PollFdRequest) return false

            return fd == other.fd &&
                    events == other.events
        }

        override fun hashCode(): Int {
            var result = fd
            result = 31 * result + events
            return result
        }

        override fun toString(): String {
            return "PollFdRequest(fd=$fd, events=$events, revents=$revents)"
        }

        fun needContinue(): Boolean {
            return revents != 0
        }
    }
}
