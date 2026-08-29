package kio.async.io

internal actual suspend fun openPipe(): AsyncRawConnection {
    return pipe()
}