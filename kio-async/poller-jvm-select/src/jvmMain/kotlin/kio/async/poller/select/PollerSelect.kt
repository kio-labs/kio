package kio.async.poller.select

import kio.async.POLL_INTEREST_ACCEPT
import kio.async.POLL_INTEREST_CONNECT
import kio.async.PollInterest
import kio.async.POLL_INTEREST_READ
import kio.async.POLL_INTEREST_WRITE
import kio.async.Poller
import kio.async.SelectionKeyWrapper
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

object Select : Poller.Factory {
    override fun create(): Poller = PollerSelect()
}

internal class PollerSelect : Poller {
    private val selector = Selector.open()

    override fun attach(handle: Any, event: PollInterest) {
        handle as SelectionKeyWrapper
        val op = event.toOp()

        val key = handle.channel.keyFor(selector)

        if (key == null || !key.isValid) {
            handle.channel.register(selector, op)
        } else {
            key.interestOps(key.interestOps() or op)
        }
    }

    override fun detach(handle: Any, event: PollInterest) {
        handle as SelectionKeyWrapper
        val key = handle.channel.keyFor(selector)
        if (key == null || !key.isValid) return

        val op = event.toOp()
        val newOps = key.interestOps() and op.inv()

        key.interestOps(newOps)
    }

    override fun poll(
        timeoutMillis: Long,
        onActive: (handle: Any, event: PollInterest) -> Unit
    ) {
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