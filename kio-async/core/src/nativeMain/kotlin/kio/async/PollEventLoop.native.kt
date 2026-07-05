package kio.async

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.io.IOException
import platform.posix.CLOCK_MONOTONIC
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.clock_gettime
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.pipe
import platform.posix.read
import platform.posix.strerror
import platform.posix.timespec
import platform.posix.write

@OptIn(ExperimentalForeignApi::class)
internal actual fun nowMillis(): Long = memScoped {
    val ts = alloc<timespec>()
    if (clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr) != 0) {
        val e = errno
        val msg = strerror(e)?.toKString()
        throw IOException("clock_gettime failed: errno=$e, message=$msg")
    }

    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun wakeupPipe() = memScoped {
    val fds = allocArray<IntVar>(2)
    check(pipe(fds) == 0)

    val wakeupReadFd: Int = fds[0]
    val wakeupWriteFd: Int = fds[1]

    setNonBlocking(wakeupReadFd)
    setNonBlocking(wakeupWriteFd)

    object : WakeupPipe {
        override val wakeupReadFD = wakeupReadFd

        override fun drainWakeup() = memScoped {
            val buf = allocArray<ByteVar>(64)

            while (true) {
                val n = read(wakeupReadFd, buf, 64.convert())
                if (n > 0) continue

                if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                    break
                }

                break
            }
        }

        override fun wakeup() = memScoped {
            val b = alloc<ByteVar>()
            b.value = 1

            val n = write(wakeupWriteFd, b.ptr, 1.convert())

            if (n < 0) {
                val e = errno
                if (e == EAGAIN || e == EWOULDBLOCK) {
                    return@memScoped
                }
                error("wakeup write failed: errno=$e")
            }
        }

        override fun close() {
            close(wakeupReadFd)
            close(wakeupWriteFd)
        }
    }
}

private fun setNonBlocking(fd: Int): Int {
    val flags = fcntl(fd, F_GETFL, 0)
    if (flags < 0) return -1
    if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) return -1
    return 0
}