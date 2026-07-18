package kio.async.io

import kio.async.AsyncRawSource

expect suspend fun openFileSource(path: String): AsyncRawSource