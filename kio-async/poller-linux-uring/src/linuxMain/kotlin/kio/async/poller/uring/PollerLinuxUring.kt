@file:OptIn(ExperimentalForeignApi::class)

package kio.async.poller.uring

import kio.async.PollInterest
import kio.async.Poller
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import linux.uring.io_uring
import linux.uring.io_uring_queue_init
import platform.posix.errno
import platform.posix.strerror

object LinuxUring : Poller.Factory {
    override fun create(): Poller = PollerLinuxUring()
}

private const val QUEUE_SIZE = 2u

private class PollerLinuxUring : Poller {
    private val arean = Arena()
    private val ioUring = arean.alloc<io_uring>()

    init {
        val result = io_uring_queue_init(QUEUE_SIZE, ioUring.ptr, 0u)
        println("TEST $result")
    }

    override fun attach(handle: Any, event: PollInterest) {
    }

    override fun detach(handle: Any, event: PollInterest) {
        TODO("Not yet implemented")
    }

    override fun poll(
        timeoutMillis: Long,
        onActive: (handle: Any, event: PollInterest) -> Unit
    ) {
        TODO("Not yet implemented")
    }

    override fun close() {
        TODO("Not yet implemented")
    }
}

private fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}
