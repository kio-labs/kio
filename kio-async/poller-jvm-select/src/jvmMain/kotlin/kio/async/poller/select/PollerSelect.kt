package kio.async.poller.select

import kio.async.PollInterestAccept
import kio.async.PollInterestConnect
import kio.async.PollInterest
import kio.async.PollInterestRead
import kio.async.PollInterestWrite
import kio.async.Poller
import kio.async.SelectionKeyWrapper
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

object Select : Poller.Factory {
    override fun create(): Poller = PollerSelect()
}

internal class PollerSelect : Poller {
    private val selector = Selector.open()

    override fun register(handle: SelectionKeyWrapper, event: PollInterest) {
        val op = event.toOp()

        val key = handle.channel.keyFor(selector)

        if (key == null || !key.isValid) {
            handle.channel.register(selector, op)
        } else {
            key.interestOps(key.interestOps() or op)
        }
    }

    override fun unRegister(handle: SelectionKeyWrapper, event: PollInterest) {
        val key = handle.channel.keyFor(selector)
        if (!key.isValid) return

        val op = event.toOp()
        val newOps = key.interestOps() and op.inv()

        key.interestOps(newOps)
    }

    override fun poll(
        timeoutMillis: Long,
        block: Poller.PollScope.() -> Unit,
    ) {
        when (timeoutMillis) {
            -1L -> selector.select()
            0L -> selector.selectNow()
            else -> selector.select(timeoutMillis)
        }

        val selectedKeys = selector.selectedKeys()
        val awakeKeys = selectedKeys.toSet()
        selectedKeys.clear()

        val scope = Poller.PollScope { handle, event ->
            val key = handle.channel.keyFor(selector)

            key != null &&
                    key.isValid &&
                    key in awakeKeys &&
                    key.readyOps() and event.toOp() != 0
        }

        block(scope)
    }

    override fun close() {
        selector.close()
    }

    private fun PollInterest.toOp(): Int = when (this) {
        PollInterestRead -> SelectionKey.OP_READ
        PollInterestWrite -> SelectionKey.OP_WRITE
        PollInterestConnect -> SelectionKey.OP_CONNECT
        PollInterestAccept -> SelectionKey.OP_ACCEPT
    }
}