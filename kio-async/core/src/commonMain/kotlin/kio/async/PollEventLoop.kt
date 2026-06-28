package kio.async

import kotlinx.coroutines.AbstractCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.disposeOnCancellation
import kotlinx.coroutines.newCoroutineContext
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

expect class PollHandle

sealed interface PollInterest
data object PollInterestRead : PollInterest
data object PollInterestWrite : PollInterest
// JVM only
data object PollInterestConnect : PollInterest
data object PollInterestAccept : PollInterest

interface Poller {

    interface Factory {
        fun create(): Poller
    }

    fun interface PollScope {
        fun isAwake(handle: PollHandle, event: PollInterest): Boolean
    }


    fun register(handle: PollHandle, event: PollInterest)
    fun unRegister(handle: PollHandle, event: PollInterest)

    /**
     * Blocks the current thread until this fd becomes ready or the timeout expires.
     *
     * @param timeoutMillis timeout in milliseconds.
     * - `-1` means wait forever.
     * - `0` means return immediately.
     * - `> 0` means wait up to the given milliseconds.
     */
    fun poll(timeoutMillis: Long, block: PollScope.() -> Unit)

    fun close()
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

    private val continuationMap: MutableMap<Pair<PollHandle, PollInterest>, Continuation<Unit>> = mutableMapOf()
    private val timerRequestMap: MutableMap<TimerRequest, Runnable> = mutableMapOf()
    private val taskQueue = ArrayDeque<Runnable>()

    private val wakeupPipe = wakeupPipe()

    init {
        nativePoller.register(wakeupPipe.wakeupReadFD, PollInterestRead)
    }

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable
    ) {
        taskQueue.addFirst(block)
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext
    ): DisposableHandle {
        val request = TimerRequest(
            TimerRequest.nextKey(),
            deadlineMillis = nowMillis() + timeMillis
        )

        registerTimer(request, block)

        return DisposableHandle {
            unRegisterTimer(request)
        }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>
    ) {
        val request = TimerRequest(
            TimerRequest.nextKey(),
            deadlineMillis = nowMillis() + timeMillis
        )
        registerTimer(request) {
            continuation.resume(Unit)
        }

        continuation.disposeOnCancellation {
            unRegisterTimer(request)
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

        // block
        nativePoller.poll(timeout) {
            resumeSleepingFds()
        }

        resumeTimers()

        wakeupPipe.drainWakeup()
    }

    private fun getNearestTimeout(): Long {
        val nearestDeadline = timerRequestMap.keys
            .minBy { it.deadlineMillis }
            .deadlineMillis
        return (nearestDeadline - nowMillis()).coerceAtLeast(0)
    }

    private fun Poller.PollScope.resumeSleepingFds() {
        fun removeFdIfAwake(fd: PollHandle, event: PollInterest): Boolean {
            val awake = isAwake(fd, event)
            if (awake) nativePoller.unRegister(fd, event)
            return awake
        }

        val it = continuationMap.iterator()
        while (it.hasNext()) {
            val (req, continuation) = it.next()
            val (handle, event) = req
            if (removeFdIfAwake(handle, event)) {
                it.remove()
                continuation.resume(Unit)
            }
        }
    }

    private fun resumeTimers() {
        if (timerRequestMap.isEmpty()) return

        val it = timerRequestMap.iterator()
        while (it.hasNext()) {
            val (req, runnable) = it.next()
            if (req.needContinue()) {
                it.remove()
                runnable.run()
            }
        }
    }

    fun registerTimer(req: TimerRequest, c: Runnable) {
        if (timerRequestMap.contains(req)) throw IllegalStateException("$req already sleep.")
        timerRequestMap[req] = c
    }

    fun unRegisterTimer(req: TimerRequest) {
        timerRequestMap.remove(req)
    }

    fun registerHandle(fd: PollHandle, event: PollInterest, c: Continuation<Unit>) {
        nativePoller.register(fd, event)
        continuationMap[fd to event] = c
    }

    fun unRegisterHandle(fd: PollHandle, event: PollInterest) {
        nativePoller.unRegister(fd, event)
        continuationMap.remove(fd to event)
        wakeupPipe.wakeup()
    }

    fun processNextEvent(): Boolean {
        taskQueue.removeLastOrNull()?.run() ?: return false
        return true
    }

    fun close() {
        nativePoller.close()
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
    val wakeupReadFD: PollHandle
    fun drainWakeup()
    fun wakeup()

    fun close()
}

@OptIn(InternalCoroutinesApi::class)
private class BlockingAIOCoroutine<T>(
    parentContext: CoroutineContext,
    private val dispatcher: AsyncPollEventDispatcher
) : AbstractCoroutine<T>(parentContext, true, true) {
    private var result: Result<T>? = null

    override fun onCompleted(value: T) {
        super.onCompleted(value)
        result = Result.success(value)
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) {
        super.onCancelled(cause, handled)
        result = Result.failure(cause)
    }

    fun joinBlocking(): T {
        while (true) {
            dispatcher.doBlockPoll()

            while (true) {
                val hasNext = dispatcher.processNextEvent()
                if (!hasNext) break
            }

            if (isCompleted) break
        }

        dispatcher.close()

        return result?.getOrThrow() ?: throw IllegalStateException("No result???")
    }
}

internal expect fun nowMillis(): Long

internal expect fun wakeupPipe(): WakeupPipe
