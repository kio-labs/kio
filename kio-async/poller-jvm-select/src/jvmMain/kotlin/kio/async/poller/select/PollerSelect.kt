package kio.async.poller.select

import kio.async.POLL_INTEREST_ACCEPT
import kio.async.POLL_INTEREST_CONNECT
import kio.async.PollInterest
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.Poller
import kio.async.PollerFactory
import kio.async.SelectionKeyWrapper
import kio.async.SuspendChannelIo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

object Select : PollerFactory {
    override fun create(): Poller = PollerSelect()
}

internal class PollerSelect : Poller, SuspendChannelIo {
    private val selector = Selector.open()

    private val continuationMap: MutableMap<Pair<SelectionKeyWrapper, PollInterest>, Continuation<Unit>> =
        mutableMapOf()

    override fun attach(handle: SelectionKeyWrapper, event: PollInterest) {
        val op = event.toOp()

        val key = handle.channel.keyFor(selector)

        if (key == null || !key.isValid) {
            handle.channel.register(selector, op)
        } else {
            key.interestOps(key.interestOps() or op)
        }
    }

    override fun detach(handle: SelectionKeyWrapper, event: PollInterest) {
        val key = handle.channel.keyFor(selector)
        if (key == null || !key.isValid) return

        val op = event.toOp()
        val newOps = key.interestOps() and op.inv()

        key.interestOps(newOps)
    }

    override suspend fun awaitIo(handle: SelectionKeyWrapper, interest: PollInterest) = suspendCancellableCoroutine { c ->
        continuationMap[handle to interest] = c
        c.invokeOnCancellation {
            continuationMap.remove(handle to interest)
        }
    }

    override fun poll(timeoutMillis: Long) {
        fun onActive(handle: Any, event: PollInterest) {
            val c  = continuationMap.remove(handle to event)
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
                onActive(channel, POLL_INTEREST_READ)
            } else if (readyOp and SelectionKey.OP_ACCEPT != 0) {
                onActive(channel, POLL_INTEREST_ACCEPT)
            } else if (readyOp and SelectionKey.OP_WRITE != 0) {
                onActive(channel, POLL_INTEREST_WRITE)
            } else if (readyOp and SelectionKey.OP_CONNECT != 0) {
                onActive(channel, POLL_INTEREST_CONNECT)
            }
        }
    }

    override fun close() {
        selector.close()
    }

    private fun PollInterest.toOp(): Int = when (this) {
        POLL_INTEREST_READ -> SelectionKey.OP_READ
        POLL_INTEREST_WRITE -> SelectionKey.OP_WRITE
        POLL_INTEREST_CONNECT -> SelectionKey.OP_CONNECT
        POLL_INTEREST_ACCEPT -> SelectionKey.OP_ACCEPT
        else -> error("invalid POLL_INTEREST $this")
    }
}