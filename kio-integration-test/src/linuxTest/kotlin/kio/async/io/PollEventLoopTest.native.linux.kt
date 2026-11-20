package kio.async.io

import kio.async.PollerFactory
import kio.async.poller
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring
import kio.async.runPollEventLoop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.CLD_EXITED
import platform.posix.WEXITED
import platform.posix._exit
import platform.posix.errno
import platform.posix.exit
import platform.posix.fork
import platform.posix.idtype_t
import platform.posix.siginfo_t
import platform.posix.strerror
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class EpollEventLoopTest : PollEventLoopTest() {
    override val factory: PollerFactory = EPoll
}

class UringEventLoopTest : PollEventLoopTest() {
    override val factory: PollerFactory = LinuxUring()

    @Test
    fun uringTaskOverflowTest() = runPollEventLoop(LinuxUring(1)) {
        val pipe = openPipe().buffered()
        val taskCount = 100
        repeat(taskCount) {
            launch {
                delay(10.milliseconds)
                pipe.sink.writeInt(1)
                pipe.sink.flush()
            }
        }

        repeat(taskCount) {
            assertEquals(1, pipe.source.readInt())
        }

        pipe.close()
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun waitIdWithIoUring() = runPollEventLoop(factory) {
        val io = currentCoroutineContext().poller.io
        val pid = fork()
        if (pid == 0) {
            _exit(42)
        } else if (pid > 0) {
            memScoped {
                val info = alloc<siginfo_t>()
                val result = io.suspendWaitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
                assertEquals(0, result)
                assertEquals(CLD_EXITED, info.si_code)
                assertEquals(42, info._sifields._sigchld.si_status)
            }
        } else {
            error("fork failed: ${strerror(errno)?.toKString()}")
        }
    }
}

internal actual suspend fun openPipe(): AsyncRawConnection {
    return pipe(0)
}
