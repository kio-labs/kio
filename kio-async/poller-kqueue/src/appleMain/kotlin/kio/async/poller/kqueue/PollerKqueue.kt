package kio.async.poller.kqueue

import kio.async.FdRegistration
import kio.async.Poller
import kio.async.PollerFactory
import kio.async.PosixSuspendIo
import kio.async.SuspendIo
import kotlinx.cinterop.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import platform.darwin.*
import platform.posix.errno
import platform.posix.strerror
import platform.posix.timespec
import kotlin.coroutines.resume

object Kqueue : PollerFactory {
    override fun create(): Poller = KqueuePoller()
}

val SuspendIo.kqueue: KqueueRegister
    get() = (this as KqueueIo).kqueueRegister

typealias KqueueFilter = Short

interface KqueueRegister {
    fun attach(ident: ULong, filter: KqueueFilter, flags: UShort, fflags: UInt = 0U, data: Long = 0L)
    fun detach(ident: ULong, filter: KqueueFilter)
    suspend fun awaitFilter(ident: ULong, filter: KqueueFilter)
}

private class KqueuePoller : Poller, KqueueRegister {
    private val kq = kqueue()

    @OptIn(ExperimentalForeignApi::class)
    private val arean = Arena()

    @OptIn(ExperimentalForeignApi::class)
    private val events = arean.allocArray<kevent>(EVENT_CAPACITY)
    private val continuationMap: MutableMap<Pair<ULong, KqueueFilter>, CancellableContinuation<Unit>> = mutableMapOf()

    @OptIn(ExperimentalForeignApi::class)
    override fun attach(ident: ULong, filter: KqueueFilter, flags: UShort, fflags: UInt, data: Long) = memScoped {
        val change = alloc<kevent> {
            this.ident = ident
            this.filter = filter
            this.flags = flags
            this.fflags = fflags
            this.data = data
            this.udata = null
        }

        if (kevent(kq, change.ptr, 1, null, 0, null) == -1) {
            throw IOException("error when register change list: ${errnoMessage()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun detach(ident: ULong, filter: KqueueFilter) = memScoped {
        val change = alloc<kevent> {
            this.ident = ident
            this.filter = filter
            flags = (EV_DELETE).toUShort()
            fflags = 0U
            data = 0
            udata = null
        }

        if (kevent(kq, change.ptr, 1, null, 0, null) == -1) {
            // Ignore error when unregister.
        }
    }

    override suspend fun awaitFilter(ident: ULong, filter: KqueueFilter) = suspendCancellableCoroutine { c ->
        continuationMap[ident to filter] = c
        c.invokeOnCancellation {
            continuationMap.remove(ident to filter)
        }
    }

    override val io: SuspendIo = KqueueIo(this)

    @OptIn(ExperimentalForeignApi::class)
    override fun poll(timeoutMillis: Long) = memScoped {
        val timeoutPtr = when {
            timeoutMillis < 0 -> null

            else -> alloc<timespec>().apply {
                tv_sec = timeoutMillis / 1000
                tv_nsec = (timeoutMillis % 1000) * 1_000_000
            }.ptr
        }

        val n = kevent(kq, null, 0, events, EVENT_CAPACITY, timeoutPtr)

        if (n < 0) {
            throw IOException("error when poll kqueue")
        }

        for (i in 0 until n) {
            val e = events[i]
            val ident = e.ident
            val filter = e.filter

            val c = continuationMap.remove(ident to filter)
            c?.resume(Unit)
        }
    }

    override fun shutdown() {
        val continuations = continuationMap.values.toList()
        continuationMap.clear()

        val cause = CancellationException("Kqueue poller shutdown")

        continuations.forEach {
            it.cancel(cause)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        arean.clear()
        platform.posix.close(kq)
    }
}

private const val EVENT_CAPACITY = 1024

@OptIn(ExperimentalForeignApi::class)
internal fun errnoMessage(): String {
    return strerror(errno)?.toKString() ?: "Unknown errno: $errno"
}

private class KqueueIo(val kqueueRegister: KqueueRegister) : SuspendIo, PosixSuspendIo(), FdRegistration {
    override suspend fun awaitForRead(fd: Int) {
        kqueueRegister.awaitFilter(fd.toULong(), EVFILT_READ.toShort())
    }

    override suspend fun awaitForWrite(fd: Int) {
        kqueueRegister.awaitFilter(fd.toULong(), EVFILT_WRITE.toShort())
    }

    override fun attachRead(fd: Int) {
        kqueueRegister.attach(fd.toULong(), EVFILT_READ.toShort(), (EV_ADD or EV_ENABLE or EV_CLEAR).toUShort())
    }

    override fun attachWrite(fd: Int) {
        kqueueRegister.attach(fd.toULong(), EVFILT_WRITE.toShort(), (EV_ADD or EV_ENABLE or EV_CLEAR).toUShort())
    }

    override fun detachRead(fd: Int) {
        kqueueRegister.detach(fd.toULong(), EVFILT_READ.toShort())
    }

    override fun detachWrite(fd: Int) {
        kqueueRegister.detach(fd.toULong(), EVFILT_WRITE.toShort())
    }
}
