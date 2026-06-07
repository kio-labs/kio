package kio.postgres.conn

import kio.network.openConnection
import kio.postegre.protocol.Message
import kio.postegre.protocol.readMessage
import kio.postegre.protocol.writePassword
import kio.postegre.protocol.writeStartupMessage
import kotlinx.coroutines.currentCoroutineContext
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
): PgConnection {
    val conn = openConnection(host, port)

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

private fun md5Hex(bytes: ByteArray): String {
    val digest = MD5()
    return digest.digest(bytes).toHex()
}

private fun ByteArray.toHex(): String =
    joinToString("") { b ->
        b.toUByte().toString(16).padStart(2, '0')
    }