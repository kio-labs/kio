package kio.postgres.conn

import org.kotlincrypto.hash.md.MD5
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

internal fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
): ByteArray {
    require(iterations > 0) {
        "SCRAM iteration count must be positive"
    }

    val saltWithBlockIndex = ByteArray(salt.size + 4)
    salt.copyInto(saltWithBlockIndex)

    // PBKDF2 block index 1, big-endian.
    saltWithBlockIndex[salt.size + 3] = 1

    var current = hmacSha256(
        key = password,
        data = saltWithBlockIndex,
    )

    val result = current.copyOf()

    repeat(iterations - 1) {
        current = hmacSha256(
            key = password,
            data = current,
        )

        for (index in result.indices) {
            result[index] =
                (result[index].toInt() xor current[index].toInt()).toByte()
        }
    }

    return result
}

internal fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = HmacSHA256(key)
    mac.update(data)
    return mac.doFinal()
}

internal fun sha256(data: ByteArray): ByteArray {
    val sha256 = SHA256()
    sha256.update(data)
    return sha256.digest()
}


internal fun md5Hex(bytes: ByteArray): String {
    val digest = MD5()
    return digest.digest(bytes).toHex()
}

internal fun ByteArray.toHex(): String =
    joinToString("") { b ->
        b.toUByte().toString(16).padStart(2, '0')
    }

internal fun constantTimeEquals(
    left: ByteArray,
    right: ByteArray,
): Boolean {
    var difference = left.size xor right.size
    val maxSize = maxOf(left.size, right.size)

    for (index in 0 until maxSize) {
        val leftByte =
            if (index < left.size) left[index].toInt() else 0
        val rightByte =
            if (index < right.size) right[index].toInt() else 0

        difference = difference or (leftByte xor rightByte)
    }

    return difference == 0
}