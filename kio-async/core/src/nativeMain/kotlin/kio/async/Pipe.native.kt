package kio.async

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.pipe

@OptIn(ExperimentalForeignApi::class)
actual fun openPipe(): Pair<AsyncSource, AsyncSink> = memScoped {
    val fds = allocArray<IntVar>(2)
    check(pipe(fds) == 0)

    val readFd: Int = fds[0]
    val writeFd: Int = fds[1]

    setNonBlocking(readFd)
    setNonBlocking(writeFd)

    asyncFdRawSource(readFd).buffered() to asyncFdRawSink(writeFd).buffered()
}
