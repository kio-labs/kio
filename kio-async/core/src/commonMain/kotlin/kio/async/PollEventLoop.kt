package kio.async

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
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.set
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

suspend fun awaitReadIo(fd: Int): Unit = suspendCancellableCoroutine { c ->
    val dispatcher = c.context[AsyncPollEventDispatcher] ?: error("not in async poll event")
    dispatcher.registerFdForRead(fd, c)

    c.invokeOnCancellation {
        println("awaitReadIo cancelled $it")
        dispatcher.unRegisterFdForRead(fd)
    }
}

suspend fun awaitWriteIo(fd: Int): Unit = suspendCancellableCoroutine { c ->
    val dispatcher = c.context[AsyncPollEventDispatcher] ?: error("not in async poll event")
    dispatcher.registerFdForWrite(fd, c)

    c.invokeOnCancellation {
        println("awaitWriteIo cancelled $it")
        dispatcher.unRegisterFdForWrite(fd)
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun <T> runPollEventLoop(
    factory: Poller.Factory,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> T
): T {
    val eventLoop = AsyncPollEventDispatcher(factory)
    val newContext = GlobalScope.newCoroutineContext(context + eventLoop)
    val coroutine = BlockingAIOCoroutine<T>(newContext, eventLoop)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking()
}

interface Poller {

    interface Factory {
        fun create(): Poller
    }

    enum class EventType {
        READ,
        WRITE,
    }

    fun registerFd(fd: Int, event: EventType)
    fun unRegisterFd(fd: Int, event: EventType)
    fun isAwake(fd: Int, event: EventType): Boolean

    /**
     * Blocks the current thread until this fd becomes ready or the timeout expires.
     *
     * @param timeoutMillis timeout in milliseconds.
     * - `-1` means wait forever.
     * - `0` means return immediately.
     * - `> 0` means wait up to the given milliseconds.
     */
    fun poll(timeoutMillis: Long)
}

@OptIn(InternalCoroutinesApi::class)
internal class AsyncPollEventDispatcher(
    factory: Poller.Factory
) : CoroutineDispatcher(), Delay {
    @OptIn(ExperimentalStdlibApi::class)
    companion object Key :
        AbstractCoroutineContextKey<ContinuationInterceptor, AsyncPollEventDispatcher>(
            ContinuationInterceptor,
            { it as? AsyncPollEventDispatcher })

    private val nativePoller = factory.create()

    // TODO: Thread safe support for this three collection
    private val readFdWithContinueMap: MutableMap<Int, Continuation<Unit>> = mutableMapOf()
    private val writeFdWithContinueMap: MutableMap<Int, Continuation<Unit>> = mutableMapOf()
    private val timerRequestMap: MutableMap<TimerRequest, Continuation<Unit>> = mutableMapOf()
    private val taskQueue = ArrayDeque<Runnable>()

    private val wakeupPipe = wakeupPipe()

    init {
        nativePoller.registerFd(wakeupPipe.wakeupReadFD, Poller.EventType.READ)
    }

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

//        if (timeout == -1L && fdWithContinueMap.keys.isEmpty()) {
//            throw IllegalStateException("Try to block indefinitely with no fd registration.")
//        }

        nativePoller.poll(timeout)
        wakeupPipe.drainWakeup()

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
        fun removeFdIfAwake(fd: Int, event: Poller.EventType): Boolean {
            val awake = nativePoller.isAwake(fd, event)
            if (awake) nativePoller.unRegisterFd(fd, event)
            return awake
        }

        fun resume(fdMap: MutableMap<Int, Continuation<Unit>>, event: Poller.EventType) {
            val it = fdMap.iterator()
            while (it.hasNext()) {
                val (req, continuation) = it.next()
                if (removeFdIfAwake(req, event)) {
                    it.remove()
                    continuation.resume(Unit)
                }
            }
        }

        resume(readFdWithContinueMap, Poller.EventType.READ)
        resume(writeFdWithContinueMap, Poller.EventType.WRITE)
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
    }

    fun registerFdForRead(fd: Int, c: Continuation<Unit>) {
        registerFd(fd, Poller.EventType.READ, c)
    }

    fun registerFdForWrite(fd: Int, c: Continuation<Unit>) {
        registerFd(fd, Poller.EventType.WRITE, c)
    }

    private fun registerFd(fd: Int, event: Poller.EventType, c: Continuation<Unit>) {
        nativePoller.registerFd(fd, event)
        when (event) {
            Poller.EventType.READ -> readFdWithContinueMap[fd] = c
            Poller.EventType.WRITE -> writeFdWithContinueMap[fd] = c
        }
    }

    fun unRegisterFdForRead(fd: Int) = unRegisterFd(fd, Poller.EventType.READ)

    fun unRegisterFdForWrite(fd: Int) = unRegisterFd(fd, Poller.EventType.WRITE)

    private fun unRegisterFd(fd: Int, event: Poller.EventType) {
        nativePoller.unRegisterFd(fd, event)
        when (event) {
            Poller.EventType.READ -> readFdWithContinueMap.remove(fd)
            Poller.EventType.WRITE -> writeFdWithContinueMap.remove(fd)
        }

        wakeupPipe.wakeup()
    }

    fun processNextEvent(): Boolean {
        taskQueue.removeLastOrNull()?.run() ?: return false
        return true
    }

    fun close() {
        wakeupPipe.close()
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
        @OptIn(ExperimentalAtomicApi::class)
        private val index = AtomicInt(0)
        @OptIn(ExperimentalAtomicApi::class)
            internal fun nextKey() = index.addAndFetch(1)
    }
}

internal interface WakeupPipe {
    val wakeupReadFD: Int
    fun drainWakeup()
    fun wakeup()

    fun close()
}

@OptIn(InternalCoroutinesApi::class)
private class BlockingAIOCoroutine<T>(
    parentContext: CoroutineContext,
    private val dispatcher: AsyncPollEventDispatcher
) : AbstractCoroutine<T>(parentContext, true, true) {
//    private val joinWorker = Worker.current

    // TODO: is this safe for multi-thread?
    private var result: Result<T>? = null

    override fun afterCompletion(state: Any?) {
        // wake up blocked thread
//        if (joinWorker != Worker.current) {
//            // Unpark waiting worker
//            println("unpark thread!!!!")
//            joinWorker.executeAfter(0L, {}) // send an empty task to unpark the waiting event loop
//        }
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

            dispatcher.processNextEvent()
//            if (!dispatcher.processNextEvent()) {
//                // no more task. sleep to wait complete
//                joinWorker.park(Long.MAX_VALUE / 1000L, true)
//            }

            if (isCompleted) break
        }

        dispatcher.close()

        return result?.getOrThrow() ?: throw IllegalStateException("No result???")
    }
}

internal expect fun nowMillis(): Long

internal expect fun wakeupPipe(): WakeupPipe
