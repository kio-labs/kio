@file:OptIn(ExperimentalForeignApi::class)

package kio.async.poller.uring

import kio.async.Poller
import kio.async.PollerFactory
import kio.async.SuspendIo
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVarOf
import kotlinx.cinterop.UIntVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.IOException
import linux.uring.EAGAIN
import linux.uring.ETIME
import linux.uring.__kernel_timespec
import linux.uring.io_uring
import linux.uring.io_uring_cqe
import linux.uring.io_uring_cqe_seen
import linux.uring.io_uring_get_sqe
import linux.uring.io_uring_peek_cqe
import linux.uring.io_uring_prep_accept
import linux.uring.io_uring_prep_cancel64
import linux.uring.io_uring_prep_read
import linux.uring.io_uring_prep_write
import linux.uring.io_uring_queue_init
import linux.uring.io_uring_sqe_set_data64
import linux.uring.io_uring_submit
import linux.uring.io_uring_wait_cqe
import linux.uring.io_uring_wait_cqe_timeout
import platform.posix.errno
import platform.posix.sockaddr
import platform.posix.strerror
import kotlin.coroutines.resume
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import linux.platform.statx
import linux.uring.ECANCELED
import linux.uring.io_uring_free_probe
import linux.uring.io_uring_get_probe_ring
import linux.uring.io_uring_op
import linux.uring.io_uring_opcode_supported
import linux.uring.io_uring_prep_bind
import linux.uring.io_uring_prep_close
import linux.uring.io_uring_prep_connect
import linux.uring.io_uring_prep_listen
import linux.uring.io_uring_prep_open
import linux.uring.io_uring_prep_pipe
import linux.uring.io_uring_prep_shutdown
import linux.uring.io_uring_prep_socket
import linux.uring.io_uring_prep_statx
import linux.uring.io_uring_queue_exit
import linux.uring.io_uring_sqe
import platform.posix.sockaddr_in
import platform.posix.stat

/**
 * Creates a [PollerFactory] backed by io_uring.
 *
 * @param entires the number of entries in the submission queue.
 * @see https://man7.org/linux/man-pages/man3/io_uring_queue_init.3.html
 */
fun LinuxUring(entires: Int = 64) = object : PollerFactory {
    override fun create(): Poller = PollerLinuxUring(entires)
}

private class PollerLinuxUring(entries: Int) : Poller, SuspendIo {
    private val arean = Arena()
    private val ring = arean.alloc<io_uring>()

    private val requestMap = mutableMapOf<ULong, UringReq>()

    private var shuttingDown = false
    private var closed = false

    override val io: SuspendIo = this

    private val bindSupported: Boolean by lazy {
        isSupported(io_uring_op.IORING_OP_BIND)
    }

    private val listenSupported: Boolean by lazy {
        isSupported(io_uring_op.IORING_OP_LISTEN)
    }

    private fun isSupported(op: io_uring_op): Boolean {
        val probe = io_uring_get_probe_ring(ring.ptr) ?: return false

        return try {
            io_uring_opcode_supported(
                probe,
                op.value.toInt()
            ) != 0
        } finally {
            io_uring_free_probe(probe)
        }
    }

    init {
        val result = io_uring_queue_init(entries.toUInt(), ring.ptr, 0u)
        if (result != 0) {
            throw IOException("exception when int uring queue. ${errnoMessage(result)}")
        }
    }

    override fun poll(timeoutMillis: Long) = memScoped {
        val submitted = io_uring_submit(ring.ptr)
        if (submitted < 0) {
            throw IOException("io_uring_submit failed: ${errnoMessage(submitted)}")
        }

        val cqeVar = alloc<CPointerVar<io_uring_cqe>>()
        val waitResult = when {
            timeoutMillis < 0L -> {
                io_uring_wait_cqe(
                    ring.ptr,
                    cqeVar.ptr
                )
            }

            timeoutMillis == 0L -> {
                io_uring_peek_cqe(
                    ring.ptr,
                    cqeVar.ptr
                )
            }

            else -> {
                val timeout = alloc<__kernel_timespec>().apply {
                    tv_sec = timeoutMillis / 1_000L
                    tv_nsec = (timeoutMillis % 1_000L) * 1_000_000L
                }

                io_uring_wait_cqe_timeout(
                    ring.ptr,
                    cqeVar.ptr,
                    timeout.ptr
                )
            }
        }

        when (waitResult) {
            -ETIME, -EAGAIN -> return@memScoped
        }

        if (waitResult < 0) {
            throw IOException("io_uring_wait_cqe failed: ${errnoMessage(waitResult)}")
        }

        consumeCeq(cqeVar)

        while (true) {
            cqeVar.value = null
            val peekResult = io_uring_peek_cqe(ring.ptr, cqeVar.ptr)

            if (peekResult == -EAGAIN) break

            if (peekResult < 0) {
                throw IOException("io_uring_peek_cqe failed: ${errnoMessage(waitResult)}")
            }

            consumeCeq(cqeVar)
        }
    }

    private fun consumeCeq(cqeVar: CPointerVar<io_uring_cqe>) {
        try {
            val actionId = cqeVar.pointed?.user_data ?: throw IOException("no user_data")
            val req = requestMap.remove(actionId) ?: return
            val result =
                cqeVar.pointed?.res ?: throw IOException("result of request $req not found.")
            when (req) {
                is UringReq.Open -> {
                    completeRequest(req.c, result)
                    req.arena.clear()
                }
                is UringReq.Close -> completeRequest(req.c, result)
                is UringReq.Read -> completeRequest(req.c, result)
                is UringReq.Statx -> {
                    completeRequest(req.c, result)
                    req.arena.clear()
                }
                is UringReq.Write -> completeRequest(req.c, result)
                is UringReq.Pipe -> completeRequest(req.c, result)
                is UringReq.ShutDown -> completeRequest(req.c, result)
                is UringReq.Bind -> completeRequest(req.c, result)
                is UringReq.Listen -> completeRequest(req.c, result)
                is UringReq.Socket -> completeRequest(req.c, result)
                is UringReq.Connect -> completeRequest(req.c, result)
                is UringReq.Accept -> completeRequest(req.c, result)
                is UringReq.Cancel -> Unit
            }
        } finally {
            io_uring_cqe_seen(ring.ptr, cqeVar.value)
        }
    }

    private fun completeRequest(c: CancellableContinuation<Int>, result: Int) {
        if (result == -ECANCELED) {
            c.cancel(CancellationException("io_uring request cancelled"))
        } else {
            c.resume(result)
        }
    }

    override suspend fun suspendWrite(
        fd: Int,
        buf: CPointer<*>,
        byte: ULong
    ): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()

        io_uring_prep_write(sqe, fd, buf, byte.toUInt(), (-1).toULong())
        val id = nextActionId()
        requestMap[id] = UringReq.Write(c)
        sqe.pointed.user_data = id

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendRead(
        fd: Int,
        bytes: CPointer<*>,
        nbyte: ULong
    ): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()

        io_uring_prep_read(sqe, fd, bytes, nbyte.toUInt(), (-1).toULong())
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Read(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendAccept(
        fd: Int,
        addr: CPointer<sockaddr_in>,
        addrLen: CPointer<UIntVarOf<UInt>>
    ): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()

        io_uring_prep_accept(sqe, fd, addr.reinterpret(), addrLen, 0)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Accept(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendConnect(
        fd: Int,
        addr: CPointer<sockaddr>,
        len: UInt
    ): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()

        io_uring_prep_connect(sqe, fd, addr.reinterpret(), len)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Connect(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendOpen(path: String?, flags: Int, mode: UInt): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()
        val arena = Arena()
        val cPath = path?.cstr?.getPointer(arena)

        io_uring_prep_open(sqe, cPath, flags, mode)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Open(arena, c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendClose(fd: Int): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()
        io_uring_prep_close(sqe, fd)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Close(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?, pipeFlags: Int): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()
        io_uring_prep_pipe(sqe, fds, pipeFlags)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Pipe(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendStatx(dirfd: Int, path: String?, flags: Int, mask: UInt, buf: CPointer<statx>?): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()

        val arena = Arena()
        val cPath = path?.cstr?.getPointer(arena)

        io_uring_prep_statx(sqe, dirfd, cPath, flags, mask, buf?.reinterpret())
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Statx(arena, c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendShutdown(fd: Int, how: Int): Int  = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()
        io_uring_prep_shutdown(sqe, fd, how)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.ShutDown(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendBind(fd: Int, addr: CPointer<sockaddr>?, addrlen: UInt): Int {
        if (!bindSupported) {
            return platform.posix.bind(fd, addr, addrlen)
        }

        return suspendCancellableCoroutine { c ->
            val sqe = takeRequestSqe()
            io_uring_prep_bind(sqe, fd, addr?.reinterpret(), addrlen)
            val id = nextActionId()
            io_uring_sqe_set_data64(sqe, id)
            requestMap[id] = UringReq.Bind(c)

            c.invokeOnCancellation {
                cancelRequest(id)
            }
        }
    }

    override suspend fun suspendListen(fd: Int, backlog: Int): Int {
        if (!listenSupported) {
            return platform.posix.listen(fd, backlog)
        }

        return suspendCancellableCoroutine { c ->
            val sqe = takeRequestSqe()
            io_uring_prep_listen(sqe, fd, backlog)
            val id = nextActionId()
            io_uring_sqe_set_data64(sqe, id)
            requestMap[id] = UringReq.Listen(c)

            c.invokeOnCancellation {
                cancelRequest(id)
            }
        }
    }

    override suspend fun suspendSocket(domain: Int, type: Int, protocol: Int): Int = suspendCancellableCoroutine { c ->
        val sqe = takeRequestSqe()
        io_uring_prep_socket(sqe, domain, type, protocol, 0U)
        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Socket(c)

        c.invokeOnCancellation {
            cancelRequest(id)
        }
    }

    override suspend fun suspendPipe(fds: CPointer<IntVarOf<Int>>?): Int {
        return platform.posix.pipe(fds)
    }

    override suspend fun suspendStat(path: String?, buf: CPointer<stat>?): Int {
        return platform.posix.stat(path, buf)
    }

    override fun shutdown() {
        check(!closed)
        if (shuttingDown) return
        shuttingDown = true

        val requestIds = requestMap
            .filterValues { it !is UringReq.Cancel }
            .keys
            .toList()

        requestIds.forEach(::cancelRequest)

        while (requestMap.isNotEmpty()) {
            poll(-1)
        }
    }

    override fun close() {
        check(!closed) {
            "io_uring is already closed"
        }

        check(requestMap.isEmpty()) {
            "Cannot close io_uring: requests are still in flight: $requestMap"
        }
        closed = true

        io_uring_queue_exit(ring.ptr)
        arean.clear()
    }

    private fun cancelRequest(requestId: ULong) {
        val sqe = takeSqe()
        io_uring_prep_cancel64(sqe, requestId, 0)

        val id = nextActionId()
        io_uring_sqe_set_data64(sqe, id)
        requestMap[id] = UringReq.Cancel(requestId)
    }

    var id = 0UL
    private fun nextActionId() = id++

    private fun takeSqe() =
        io_uring_get_sqe(ring.ptr)
            ?: throw IOException("No available SQE.")

    private fun takeRequestSqe(): CPointer<io_uring_sqe> {
        check(!shuttingDown && !closed) {
            "io_uring is shutting down or closed"
        }

        return takeSqe()
    }
}

private sealed interface UringReq {
    data class Cancel(val requestId: ULong) : UringReq
    data class Read(val c: CancellableContinuation<Int>) : UringReq
    data class Accept(val c: CancellableContinuation<Int>) : UringReq
    data class Connect(val c: CancellableContinuation<Int>) : UringReq
    data class Write(val c: CancellableContinuation<Int>) : UringReq
    data class Open(val arena: Arena, val c: CancellableContinuation<Int>) : UringReq
    data class Close(val c: CancellableContinuation<Int>) : UringReq
    data class Pipe(val c: CancellableContinuation<Int>) : UringReq
    data class Statx(val arena: Arena, val c: CancellableContinuation<Int>) : UringReq
    data class ShutDown(val c: CancellableContinuation<Int>) : UringReq
    data class Bind(val c: CancellableContinuation<Int>) : UringReq
    data class Listen(val c: CancellableContinuation<Int>) : UringReq
    data class Socket(val c: CancellableContinuation<Int>) : UringReq
}

private fun errnoMessage(result: Int? = null): String {
    val code = result?.times(-1)
    return strerror(code ?: errno)?.toKString() ?: "Unknown errno: $errno"
}
