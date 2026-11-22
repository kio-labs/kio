package kio.async.io

import kio.async.PollerFactory
import kio.async.buffered
import kio.async.fdRegistration
import kio.async.poller
import kio.async.poller.epoll.epoll
import kio.async.poller.epoll.EPoll
import kio.async.poller.uring.LinuxUring
import kio.async.posix
import kio.async.readString
import kio.async.runPollEventLoop
import kio.async.writeString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.linux.EPOLLIN
import platform.posix.close
import platform.posix.CLD_EXITED
import platform.posix.WEXITED
import platform.posix._exit
import platform.posix.errno
import platform.posix.fork
import platform.posix.idtype_t
import platform.posix.siginfo_t
import platform.posix.strerror
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class EpollEventLoopTest : PollEventLoopTest() {
    override val factory: PollerFactory = EPoll

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun waitIdWithEPoll() = runPollEventLoop(factory) {
        val io = currentCoroutineContext().poller.io
        val pid = fork()
        if (pid == 0) _exit(42)
        check(pid > 0) { "fork failed: ${strerror(errno)?.toKString()}" }

        var pidFd = -1
        try {
            pidFd = io.pidfdOpen(pid)
            io.epoll.attach(pidFd, EPOLLIN)
            withTimeout(5_000.milliseconds) { io.epoll.awaitIo(pidFd, EPOLLIN) }
            memScoped {
                val info = alloc<siginfo_t>()
                val result: Int = io.waitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
                assertEquals(0, result)
                assertEquals(pid, info._sifields._sigchld.si_pid)
                assertEquals(CLD_EXITED, info.si_code)
                assertEquals(42, info._sifields._sigchld.si_status)
            }
        } finally {
            io.epoll.detach(pidFd, EPOLLIN)
            close(pidFd)
        }
    }
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
                val result = io.waitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
                assertEquals(0, result)
                assertEquals(CLD_EXITED, info.si_code)
                assertEquals(42, info._sifields._sigchld.si_status)
            }
        } else {
            error("fork failed: ${strerror(errno)?.toKString()}")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun subProcessCommandTest() = runPollEventLoop(factory) {
        val io = currentCoroutineContext().poller.io

        val pid = posixSpawn("sh", listOf("-c", "exit \$MY_CODE")) {
            env("MY_CODE" to "42")
        }

        memScoped {
            val info = alloc<siginfo_t>()
            val rc = io.waitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
            assertEquals(0, rc)
            assertEquals(pid, info.pid)
            assertTrue(info.isExited)
            assertEquals(42, info.exitStatus)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun inputOutputRedirectCommandTest() = runPollEventLoop(factory) {
        val poller = currentCoroutineContext().poller
        val io = poller.io

        memScoped {
            val inFds = allocArray<IntVar>(2)
            val outFds = allocArray<IntVar>(2)
            check(io.posix.pipe(inFds) == 0)
            check(io.posix.pipe(outFds) == 0)

            val stdinRead = inFds[0]
            val stdinWrite = inFds[1]
            val stdoutRead = outFds[0]
            val stdoutWrite = outFds[1]

            val pid = posixSpawn("tr", listOf("a-z", "A-Z")) {
                dup2(stdinRead, 0)
                dup2(stdoutWrite, 1)
                close(stdinWrite)
                close(stdoutRead)
            }

            io.posix.close(stdinRead)
            io.posix.close(stdoutWrite)

            io.fdRegistration?.attachWrite(stdinWrite)
            io.fdRegistration?.attachRead(stdoutRead)

            val sink = io.asyncRawSink(stdinWrite).buffered()
            val source = io.asyncRawSource(stdoutRead).buffered()

            sink.writeString("hello world\n")
            sink.flush()
            sink.close()

            val output = source.readString()
            assertEquals("HELLO WORLD\n", output)
            source.close()

            val info = alloc<siginfo_t>()
            val rc = io.waitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
            assertEquals(0, rc)
            assertEquals(pid, info.pid)
            assertTrue(info.isExited)
            assertEquals(0, info.exitStatus)
        }
    }
}

internal actual suspend fun openPipe(): AsyncRawConnection {
    return pipe(0)
}
