package kio.async

import kotlinx.io.Buffer
import kotlinx.io.DelicateIoApi
import kotlinx.io.InternalIoApi
import kotlinx.io.writeString
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind.EXACTLY_ONCE
import kotlin.contracts.contract


@OptIn(DelicateIoApi::class)
suspend fun AsyncSink.writeString(string: String, startIndex: Int = 0, endIndex: Int = string.length) {
    checkBounds(string.length, startIndex, endIndex)

    writeToInternalBuffer {
        it.writeString(string, startIndex, endIndex)
    }
}

@DelicateIoApi
@OptIn(InternalIoApi::class, ExperimentalContracts::class)
suspend inline fun AsyncSink.writeToInternalBuffer(lambda: (Buffer) -> Unit) {
    contract {
        callsInPlace(lambda, EXACTLY_ONCE)
    }
    lambda(this.buffer)
    this.hintEmit()
}