package kio.async

import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.errno

internal suspend inline fun posixCall(crossinline func: () -> Int, crossinline waitIO: suspend () -> Unit): Int {
    while (true) {
        val ret = func()

        if (ret >= 0) {
            return ret
        }

        val error = errno

        if (error != EAGAIN && error != EWOULDBLOCK) {
            return -error
        }

        waitIO()
    }
}