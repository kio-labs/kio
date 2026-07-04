package kio.network

import platform.linux.inet_addr

internal actual fun inet_addr(host: String?): UInt {
    return inet_addr(host)
}