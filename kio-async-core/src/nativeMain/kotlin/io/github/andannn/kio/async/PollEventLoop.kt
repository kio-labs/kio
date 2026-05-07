package io.github.andannn.kio.async

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.AbstractCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.disposeOnCancellation
import kotlinx.coroutines.newCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import platform.posix.CLOCK_MONOTONIC
import platform.posix.POLLRDNORM
import platform.posix.POLLWRNORM
import platform.posix.clock_gettime
import platform.posix.errno
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.strerror
import platform.posix.timespec
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.AbstractCoroutineContextKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

suspend fun awaitReadIo(fd: Int): Unit = suspendCancellableCoroutine { c ->
    val dispatcher = c.context[AsyncPollEventDispatcher] ?: error("not in async poll event")
    val fdRequest = PollFdRequest(fd, POLLRDNORM)
    dispatcher.registerFd(fdRequest, c)

    c.invokeOnCancellation {
        println("awaitReadIo cancelled $it")
        dispatcher.unRegisterFd(fdRequest)
    }
}

suspend fun awaitWriteIo(fd: Int): Unit = suspendCancellableCoroutine { c ->
    val dispatcher = c.context[AsyncPollEventDispatcher] ?: error("not in async poll event")
    val fdRequest = PollFdRequest(fd, POLLWRNORM)
    dispatcher.registerFd(fdRequest, c)

    c.invokeOnCancellation {
        println("awaitWriteIo cancelled $it")
        dispatcher.unRegisterFd(fdRequest)
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun <T> runPollEventLoop(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T
): T {
    val eventLoop = AsyncPollEventDispatcher()
    val newContext = GlobalScope.newCoroutineContext(context + eventLoop)
    val coroutine = BlockingAIOCoroutine<T>(newContext, eventLoop)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking()
}

@OptIn(InternalCoroutinesApi::class)
internal class AsyncPollEventDispatcher : CoroutineDispatcher(), Delay {
    @OptIn(ExperimentalStdlibApi::class)
    companion object Key :
        AbstractCoroutineContextKey<ContinuationInterceptor, AsyncPollEventDispatcher>(
            ContinuationInterceptor,
            { it as? AsyncPollEventDispatcher })

    // TODO: Thread safe support for this three collection
    private val pollFdRequestMap: MutableMap<PollFdRequest, Continuation<Unit>> = mutableMapOf()
    private val timerRequestMap: MutableMap<TimerRequest, Continuation<Unit>> = mutableMapOf()
    private val taskQueue = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable
    ) {
        taskQueue.addFirst(block)
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>
    ) {
        val timer = TimerRequest(
            TimerRequest.nextKey(),
            deadlineMillis = nowMillis() + timeMillis
        )
        registerTimer(timer, continuation)

        continuation.disposeOnCancellation {
            unRegisterTimer(timer)
        }
    }

    fun doBlockPoll() {
        val currentPollRequests = pollFdRequestMap.keys.toList()

        // case1: having active tasks, do not block. timeout = 0
        // case2: no tasks, having timer requests. timeout = nearest timeout.
        // case3: no tasks, no timer requests. timeout = -1 (indefinitely block).
        val timeout = when {
            taskQueue.isNotEmpty() -> 0
            timerRequestMap.isNotEmpty() -> {
                getNearestTimeout()
            }

            else -> -1
        }

        if (timeout == -1L && currentPollRequests.isEmpty()) {
            throw IllegalStateException("Try to block indefinitely with no fd registration.")
        }

        poll(currentPollRequests, timeout)

        // resume all continuation.
        resumeSleepingFds()
        resumeTimers()
    }

    private fun getNearestTimeout(): Long {
        val nearestDeadline = timerRequestMap.keys
            .minBy { it.deadlineMillis }
            .deadlineMillis
        return (nearestDeadline - nowMillis()).coerceAtLeast(0)
    }

    private fun resumeSleepingFds() {
        val it = pollFdRequestMap.iterator()
        while (it.hasNext()) {
            val (req, continuation) = it.next()
            if (req.needContinue()) {
                it.remove()
                continuation.resume(Unit)
            }
        }
    }

    private fun resumeTimers() {
        val it = timerRequestMap.iterator()
        while (it.hasNext()) {
            val (req, continuation) = it.next()
            if (req.needContinue()) {
                it.remove()
                continuation.resume(Unit)
            }
        }
    }

    fun registerTimer(req: TimerRequest, c: Continuation<Unit>) {
        if (timerRequestMap.contains(req)) throw IllegalStateException("$req already sleep.")
        timerRequestMap[req] = c
    }

    fun unRegisterTimer(req: TimerRequest) {
        timerRequestMap.remove(req)
            ?: throw IllegalStateException("try to unRegisterTimer but $req not exist.")
    }

    fun registerFd(fd: PollFdRequest, c: Continuation<Unit>) {
        if (pollFdRequestMap.contains(fd)) throw IllegalStateException("$fd already sleep.")
        pollFdRequestMap[fd] = c
    }

    fun unRegisterFd(fd: PollFdRequest) {
        pollFdRequestMap.remove(fd)
            ?: throw IllegalStateException("try to unRegisterFd but $fd not exist.")
    }

    fun processNextEvent(): Boolean {
        taskQueue.removeLastOrNull()?.run() ?: return false
        return true
    }
}

@OptIn(ObsoleteWorkersApi::class, InternalCoroutinesApi::class)
private class BlockingAIOCoroutine<T>(
    parentContext: CoroutineContext,
    private val dispatcher: AsyncPollEventDispatcher
) : AbstractCoroutine<T>(parentContext, true, true) {
    private val joinWorker = Worker.current

    // TODO: is this safe for multi-thread?
    private var result: Result<T>? = null

    override fun afterCompletion(state: Any?) {
        // wake up blocked thread
        if (joinWorker != Worker.current) {
            // Unpark waiting worker
            println("unpark thread!!!!")
            joinWorker.executeAfter(0L, {}) // send an empty task to unpark the waiting event loop
        }
    }

    override fun onCompleted(value: T) {
        super.onCompleted(value)
        result = Result.success(value)
        println("Complete !!! $value")
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) {
        super.onCancelled(cause, handled)
        result = Result.failure(cause)
    }

    fun joinBlocking(): T {
        while (true) {
            dispatcher.doBlockPoll()

            if (!dispatcher.processNextEvent()) {
                // no more task. sleep to wait complete
                joinWorker.park(Long.MAX_VALUE / 1000L, true)
            }

            if (isCompleted) break
        }

        return result?.getOrThrow() ?: throw IllegalStateException("No result???")
    }
}

internal class TimerRequest(
    val id: Int,
    val deadlineMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimerRequest) return false

        return id == other.id &&
                deadlineMillis == other.deadlineMillis
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + deadlineMillis.hashCode()
        return result
    }

    override fun toString(): String {
        return "TimerRequest(id=$id, deadlineMillis=$deadlineMillis)"
    }

    fun needContinue(): Boolean {
        return (deadlineMillis - nowMillis()) <= 0
    }

    companion object {
        private val index = AtomicInt(0)
        internal fun nextKey() = index.getAndAdd(1)
    }
}


internal class PollFdRequest(
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

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
internal fun poll(fds: List<PollFdRequest>, timeoutMillis: Long): Unit = memScoped {
// TODO: avoid alloc memory in each poll
    val nativePollfd = allocArray<pollfd>(fds.size) { i ->
        fd = fds[i].fd
        events = fds[i].events.convert()
    }

    val res = poll(nativePollfd, fds.size.convert(), timeoutMillis.toInt())

    if (res < 0) {
        val e = errno
        val msg = strerror(e)?.toKString()
        throw IOException("poll failed: errno=$e, message=$msg")
    }

    fds.onEachIndexed { i, request ->
        request.revents = nativePollfd[i].revents.toInt()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun nowMillis(): Long = memScoped {
    val ts = alloc<timespec>()
    if (clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr) != 0) {
        val e = errno
        val msg = strerror(e)?.toKString()
        throw IOException("clock_gettime failed: errno=$e, message=$msg")
    }

    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}