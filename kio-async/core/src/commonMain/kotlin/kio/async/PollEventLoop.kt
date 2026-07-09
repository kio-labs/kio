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

val CoroutineContext.poller: Poller
    get() = get(ContinuationInterceptor) as? Poller ?: error("No poller registered.")

interface Poller {
    interface Factory {
        fun create(): Poller
    }

    fun attach(handle: Any, event: PollInterest)
    fun detach(handle: Any, event: PollInterest)

    /**
     * Blocks the current thread until this fd becomes ready or the timeout expires.
     *
     * @param timeoutMillis timeout in milliseconds.
     * - `-1` means wait forever.
     * - `0` means return immediately.
     * - `> 0` means wait up to the given milliseconds.
     */
    fun poll(timeoutMillis: Long, onActive: (handle: Any, event: PollInterest) -> Unit)

    fun close()
}

typealias PollInterest = Int

const val POLL_INTEREST_READ = 1
const val POLL_INTEREST_WRITE = 2

// JVM only
const val POLL_INTEREST_CONNECT = 4
const val POLL_INTEREST_ACCEPT = 8

suspend fun awaitIo(handle: Any, interest: PollInterest): Unit = suspendCancellableCoroutine { c ->
    val dispatcher = c.context[AsyncPollEventDispatcher] ?: error("not in async poll event")
    dispatcher.registerHandle(handle, interest, c)

    c.invokeOnCancellation {
        dispatcher.unRegisterHandle(handle, interest)
    }
}

@OptIn(InternalCoroutinesApi::class)
internal class AsyncPollEventDispatcher(
    factory: Poller.Factory,
    private val nativePoller: Poller = factory.create()
) : CoroutineDispatcher(), Delay, Poller by nativePoller {
    @OptIn(ExperimentalStdlibApi::class)
    companion object Key :
        AbstractCoroutineContextKey<ContinuationInterceptor, AsyncPollEventDispatcher>(
            ContinuationInterceptor,
            { it as? AsyncPollEventDispatcher })

    private val continuationMap: MutableMap<Pair<Any, PollInterest>, Continuation<Unit>> =
        mutableMapOf()
    private val timerRequestMap: MutableMap<TimerRequest, Runnable> = mutableMapOf()
    private val taskQueue = ArrayDeque<Runnable>()

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
        nativePoller.poll(timeout, ::resumeSleepingFd)

        resumeTimers()
    }

    private fun getNearestTimeout(): Long {
        val nearestDeadline = timerRequestMap.keys
            .minBy { it.deadlineMillis }
            .deadlineMillis
        return (nearestDeadline - nowMillis()).coerceAtLeast(0)
    }

    private fun resumeSleepingFd(handle: Any, interest: PollInterest) {
        val c = continuationMap.remove(handle to interest)
        c?.resume(Unit)
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

    private fun registerTimer(req: TimerRequest, c: Runnable) {
        if (timerRequestMap.contains(req)) throw IllegalStateException("$req already sleep.")
        timerRequestMap[req] = c
    }

    private fun unRegisterTimer(req: TimerRequest) {
        timerRequestMap.remove(req)
    }

    fun registerHandle(handle: Any, event: PollInterest, c: Continuation<Unit>) {
        continuationMap[handle to event] = c
    }

    fun unRegisterHandle(handle: Any, event: PollInterest) {
        continuationMap.remove(handle to event)
    }

    fun processNextEvent(): Boolean {
        taskQueue.removeLastOrNull()?.run() ?: return false
        return true
    }

    override fun close() {
        nativePoller.close()
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
    val wakeupReadFD: Any
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
