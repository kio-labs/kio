package kio.async

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations

@OptIn(UnsafeByteStringApi::class)
suspend fun AsyncSource.readByteString(): ByteString {
    return UnsafeByteStringOperations.wrapUnsafe(readByteArray())
}