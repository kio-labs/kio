package kio.async.poller.select

import kio.async.Poller
import kio.async.PollerFactory
import kio.async.SelectionKeyWrapper
import kio.async.SuspendChannelIo
import kio.async.SuspendIo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.coroutines.resume

object Select : PollerFactory {
    override fun create(): Poller = PollerSelect()
}

internal class PollerSelect : Poller, SuspendChannelIo {
    private val selector = Selector.open()

    private val continuationMap: MutableMap<Pair<SelectionKeyWrapper, Int>, CancellableContinuation<Unit>> =
        mutableMapOf()

    override fun attach(handle: SelectionKeyWrapper, selectionKey: Int) {
        val key = handle.channel.keyFor(selector)

        if (key == null || !key.isValid) {
            handle.channel.register(selector, selectionKey)
        } else {
            key.interestOps(key.interestOps() or selectionKey)
        }
    }

    override fun detach(handle: SelectionKeyWrapper, selectionKey: Int) {
        val key = handle.channel.keyFor(selector)
        if (key == null || !key.isValid) return

        val newOps = key.interestOps() and selectionKey.inv()

        key.interestOps(newOps)
    }

    override suspend fun awaitIo(handle: SelectionKeyWrapper, selectionKey: Int) = suspendCancellableCoroutine { c ->
        continuationMap[handle to selectionKey] = c
        c.invokeOnCancellation {
            continuationMap.remove(handle to selectionKey)
        }
    }

    override val io: SuspendIo = this

    override fun poll(timeoutMillis: Long) {
        fun onActive(handle: Any, selectionKey: Int) {
            val c  = continuationMap.remove(handle to selectionKey)
            c?.resume(Unit)
        }
        when (timeoutMillis) {
            -1L -> selector.select()
            0L -> selector.selectNow()
            else -> selector.select(timeoutMillis)
        }

        val selectedKeys = selector.selectedKeys()
        for (key in selectedKeys) {
            if (!key.isValid) continue

            val channel = SelectionKeyWrapper(key.channel())
            val readyOp = key.readyOps()
            if (readyOp and SelectionKey.OP_READ != 0) {
                onActive(channel, SelectionKey.OP_READ)
            } else if (readyOp and SelectionKey.OP_ACCEPT != 0) {
                onActive(channel, SelectionKey.OP_ACCEPT)
            } else if (readyOp and SelectionKey.OP_WRITE != 0) {
                onActive(channel, SelectionKey.OP_WRITE)
            } else if (readyOp and SelectionKey.OP_CONNECT != 0) {
                onActive(channel, SelectionKey.OP_CONNECT)
            }
        }
    }

    override fun shutdown() {
        val continuations = continuationMap.values.toList()
        continuationMap.clear()

        val cause = CancellationException("Select poller shutdown")

        continuations.forEach {
            it.cancel(cause)
        }
    }

    override fun close() {
        selector.close()
    }
}