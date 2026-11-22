package kio.async.io

import kio.async.*
import kio.async.poller.kqueue.Kqueue
import kio.async.poller.kqueue.kqueue
import kotlinx.cinterop.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import platform.darwin.EVFILT_PROC
import platform.darwin.EV_ADD
import platform.darwin.EV_ENABLE
import platform.darwin.EV_ONESHOT
import platform.darwin.NOTE_EXIT
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

class KqueueEventLoopTest : PollEventLoopTest() {
    override val factory: PollerFactory = Kqueue

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun waitForProcessExitWithKqueue() = runPollEventLoop(factory) {
        val poller = currentCoroutineContext().poller
        val io = poller.io
        val pid = fork()
        if (pid == 0) {
            _exit(42)
        } else if (pid > 0) {
            io.kqueue.attach(pid.toULong(), EVFILT_PROC.toShort(), (EV_ADD or EV_ENABLE or EV_ONESHOT).toUShort(), NOTE_EXIT)

            val job = launch { awaitCancellation() }
            io.kqueue.awaitFilter(pid.toULong(), EVFILT_PROC.toShort())
            job.cancel()
            memScoped {
                val info = alloc<siginfo_t>()
                val result = io.waitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
                assertEquals(0, result)
                assertEquals(CLD_EXITED, info.si_code)
                assertEquals(42, info.si_status)
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

        io.kqueue.attach(pid.toULong(), EVFILT_PROC.toShort(), (EV_ADD or EV_ENABLE or EV_ONESHOT).toUShort(), NOTE_EXIT)
        memScoped {
            val info = alloc<siginfo_t>()

            io.kqueue.awaitFilter(pid.toULong(), EVFILT_PROC.toShort())
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

            io.kqueue.attach(pid.toULong(), EVFILT_PROC.toShort(), (EV_ADD or EV_ENABLE or EV_ONESHOT).toUShort(), NOTE_EXIT)

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
            io.kqueue.awaitFilter(pid.toULong(), EVFILT_PROC.toShort())
            val rc = io.waitid(idtype_t.P_PID, pid.toUInt(), info.ptr, WEXITED)
            assertEquals(0, rc)
            assertEquals(pid, info.pid)
            assertTrue(info.isExited)
            assertEquals(0, info.exitStatus)
        }
    }
}
