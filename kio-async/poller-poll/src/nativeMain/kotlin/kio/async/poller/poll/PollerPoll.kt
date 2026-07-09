package kio.async.poller.poll

import kio.async.PollInterest
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.Poller
import kio.async.PollerFactory
import kio.async.polling.PollingSuspendIo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import platform.posix.POLLRDNORM
import platform.posix.POLLWRNORM
import platform.posix.errno
import platform.posix.pollfd
import platform.posix.strerror
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.native.concurrent.ObsoleteWorkersApi

object PosixPoll : PollerFactory {
    override fun create(): Poller = NativePoller()
}

private class NativePoller :Poller, PollingSuspendIo {
    private val pollFdRequestMap: MutableMap<Pair<Int, PollInterest>, PollFdRequest> =
        mutableMapOf()
    private val continuationMap: MutableMap<Pair<Int, PollInterest>, Continuation<Unit>> =
        mutableMapOf()

    override fun attach(fd: Int, event: PollInterest) {
        val fdRequest = when (event) {
            POLL_INTEREST_READ -> PollFdRequest(fd, POLLRDNORM)
            POLL_INTEREST_WRITE -> PollFdRequest(fd, POLLWRNORM)
            else -> error("never")
        }

        pollFdRequestMap[fd to event] = fdRequest
    }

    override fun detach(fd: Int, event: PollInterest) {
        pollFdRequestMap.remove(fd to event)
    }

    override suspend fun awaitIo(handle: Int, interest: PollInterest) = suspendCancellableCoroutine { c ->
        continuationMap[handle to interest] = c
        c.invokeOnCancellation {
            continuationMap.remove(handle to interest)
        }
    }

    override fun poll(timeoutMillis: Long) {
        nativePoll(pollFdRequestMap.values.toList(), timeoutMillis)
        pollFdRequestMap.forEach { entry ->
            val (handle, event) = entry.key
            val req = entry.value
            if (req.needContinue()) {
                val c = continuationMap.remove(handle to event)
                c?.resume(Unit)
            }
        }
    }

    override fun close() = Unit

    @OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class, UnsafeNumber::class)
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
