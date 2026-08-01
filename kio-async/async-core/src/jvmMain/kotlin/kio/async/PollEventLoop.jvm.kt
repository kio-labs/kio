package kio.async

import java.nio.channels.SelectableChannel

data class SelectionKeyWrapper constructor(
    val channel: SelectableChannel,
)

internal actual fun nowMillis(): Long {
    return System.nanoTime() / 1_000_000L
}

