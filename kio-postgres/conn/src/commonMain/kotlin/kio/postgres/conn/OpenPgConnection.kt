package kio.postgres.conn

import kio.async.io.AsyncConnection
import kio.async.io.buffered
import kio.async.io.openConnection
import kio.postegre.protocol.Message
import kio.postegre.protocol.readMessage
import kio.postegre.protocol.writePassword
import kio.postegre.protocol.writeStartTlsMessage
import kio.postegre.protocol.writeStartupMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.IOException
import org.kotlincrypto.hash.md.MD5

suspend fun openPgConnection(
    host: String,
    port: Int,
    user: String,
    password: String? = null,
    database: String? = null,
    applicationName: String? = null,
    options: String? = null,
    onNotice: (PgException) -> Unit = {},
    tlsNegotiation: TlsNegotiation = TlsNegotiation.PREFER,
    tlsWrapper: ((AsyncConnection) -> AsyncConnection)? = null,
): PgConnection {
    val conn = openConnection(host, port).buffered()
        .negotiationTlsConnection(tlsNegotiation, tlsWrapper)

    val params = buildMap {
        put("user", user)
        database?.let { put("database", it) }
        applicationName?.let { put("application_name", it) }
        options?.let { put("options", it) }
    }
    conn.sink.writeStartupMessage(params)
    conn.sink.flush()

    var pid: Int? = null
    var secretKey: ByteArray? = null
    val parameterStatuses: MutableMap<String, String> = mutableMapOf()

    while (true) {
        when (val msg = conn.source.readMessage()) {
            is Message.AuthenticationOk -> {
// TODO:
            }

            is Message.AuthenticationMd5Password -> {
                check(password != null)

                val pw =
                    md5Hex(md5Hex((password + user).encodeToByteArray()).encodeToByteArray() + msg.salt)
                conn.sink.writePassword("md5$pw")
                conn.sink.flush()
            }

            is Message.AuthenticationCleartextPassword -> {
                conn.sink.writePassword(password ?: error("password must be set."))
                conn.sink.flush()
            }

            is Message.BackendKeyData -> {
                pid = msg.processId
                secretKey = msg.secretKey
            }

            is Message.ParameterStatus -> {
                parameterStatuses[msg.name] = msg.value
            }

            is Message.ErrorResponse -> {
                conn.close()
                throw buildPgException(msg.errors)
            }

            is Message.ReadyForQuery -> break

            else -> {
                error("received unexpected message $msg")
            }
        }
    }

    check(pid != null && secretKey != null)
    return InternalPgConnection(
        parentContext = currentCoroutineContext(),
        conn = conn,
        parameterStatuses = parameterStatuses,
        host = host,
        port = port,
        pid = pid,
        secretKey = secretKey,
        onNotice = onNotice,
    )
}

enum class TlsNegotiation {
    DIRECT,
    PREFER,
    REQUIRE,
}
private fun md5Hex(bytes: ByteArray): String {
    val digest = MD5()
    return digest.digest(bytes).toHex()
}

private fun ByteArray.toHex(): String =
    joinToString("") { b ->
        b.toUByte().toString(16).padStart(2, '0')
    }

private suspend fun AsyncConnection.negotiationTlsConnection(
    tlsNegotiation: TlsNegotiation,
    tlsWrapper: ((AsyncConnection) -> AsyncConnection)?
): AsyncConnection {
    if (tlsWrapper == null) return this

    if (tlsNegotiation == TlsNegotiation.DIRECT) return tlsWrapper(this)

    sink.writeStartTlsMessage()
    sink.flush()

    val response = source.readByte()

    if (response.toInt() != 'S'.code) {
        if (tlsNegotiation == TlsNegotiation.REQUIRE) {
            throw IOException("Require tls connection, but pg server say no.")
        }

        return this
    }

    return tlsWrapper(this)
}
