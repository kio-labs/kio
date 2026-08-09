package kio.postgres.conn

import kio.async.io.AsyncConnection
import kio.async.io.buffered
import kio.async.io.openConnection
import kio.postgres.protocol.Message
import kio.postgres.protocol.readMessage
import kio.postgres.protocol.writePassword
import kio.postgres.protocol.writeSASLInitialResponse
import kio.postgres.protocol.writeSASLResponse
import kio.postgres.protocol.writeStartTlsMessage
import kio.postgres.protocol.writeStartupMessage
import kio.tls.SslConnection
import kio.tls.TlsException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.io.IOException
import kotlin.experimental.xor
import kotlin.io.encoding.Base64
import kotlin.random.Random

suspend fun openPgConnection(
    host: String,
    port: Int,
    user: String,
    password: String? = null,
    database: String? = null,
    applicationName: String? = null,
    options: String? = null,
    onNotice: (PgException) -> Unit = {},
    tlsWrapper: ((AsyncConnection) -> AsyncConnection)? = null,
    tlsNegotiation: TlsNegotiation = TlsNegotiation.PREFER,
    channelBinding: ChannelBinding = ChannelBinding.PREFER
): PgConnection {
    val conn = openConnection(host, port).buffered()
        .negotiationTlsConnection(tlsNegotiation, tlsWrapper)

    val params = buildMap {
        put("user", user)
        database?.let { put("database", it) }
        applicationName?.let { put("application_name", it) }
        options?.let { put("options", it) }
    }

    try {
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

                is Message.AuthenticationSasl -> {
                    conn.scramAuth(
                        msg.mechanisms,
                        password ?: error("password must be set."),
                        channelBinding
                    )
                }

                is Message.BackendKeyData -> {
                    pid = msg.processId
                    secretKey = msg.secretKey
                }

                is Message.ParameterStatus -> {
                    parameterStatuses[msg.name] = msg.value
                }

                is Message.ErrorResponse -> {
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
    } catch (t: IOException) {
        conn.close()
        throw t
    }
}

enum class ChannelBinding {
    DISABLE,
    PREFER,
    REQUIRE,
}

enum class TlsNegotiation {
    DIRECT,
    PREFER,
    REQUIRE,
}

private suspend fun AsyncConnection.scramAuth(
    mechanisms: List<String>,
    password: String,
    channelBinding: ChannelBinding
) {
    val serverHasPlus = mechanisms.contains(SCRAM_SHA256_PLUS)
    if (channelBinding == ChannelBinding.REQUIRE && !serverHasPlus) {
        throw IllegalStateException("channel binding required but server does not support SCRAM-SHA-256-PLUS")
    }

    val isTls = this is SslConnection

    var selectMechanism = SCRAM_SHA256
    var bindingData: ByteArray? = null

    if (isTls && channelBinding != ChannelBinding.DISABLE) {
        try {
            bindingData = this.getTLSCertificateHash()
        } catch (t: TlsException) {
            if (channelBinding == ChannelBinding.REQUIRE) {
                throw IllegalStateException("channel binding required but failed to get server certificate hash.", t)
            }
        }
        if (bindingData != null && serverHasPlus) {
            selectMechanism = SCRAM_SHA256_PLUS
        }
    }
    if (channelBinding == ChannelBinding.REQUIRE && selectMechanism != SCRAM_SHA256_PLUS) {
        throw IllegalStateException("channel binding required but selected mechanism is not SCRAM-SHA-256-PLUS.")
    }

    // write client first message.
    val clientNonce = Base64.encode(Random.nextBytes(18))
    val clientFirstMessageBare = "n=,r=$clientNonce"
    val clientGS2Header = when(selectMechanism) {
        SCRAM_SHA256 -> "n,,"
        SCRAM_SHA256_PLUS -> "p=tls-server-end-point,,"
        else -> throw IllegalStateException("UnSupport mechanism $selectMechanism")
    }
    val clientFirstMessage = "$clientGS2Header$clientFirstMessageBare"
    sink.writeSASLInitialResponse(
        mechanism = selectMechanism,
        data = clientFirstMessage.encodeToByteArray()
    )
    sink.flush()

    // receive server first message.
    val saslContinue = waitAuthenticationSaslContinue()
    val serverFirstMessage = saslContinue.mechanism
    val serverFirstMessagesList = serverFirstMessage.split(',').toMutableList()
    val clientAndServerNotice = serverFirstMessagesList.firstOrNull { it.startsWith("r=") }
        ?.removePrefix("r=")
        ?: throw IOException("invalid SCRAM server-first-message received from server: did not include r=")
    val saltStr = serverFirstMessagesList.firstOrNull { it.startsWith("s=") }
        ?.removePrefix("s=")
        ?: throw IOException("invalid SCRAM server-first-message received from server: did not include s=")
    val iterationStr = serverFirstMessagesList.firstOrNull { it.startsWith("i=") }
        ?.removePrefix("i=")
        ?: throw IOException("invalid SCRAM server-first-message received from server: did not include i=")

    val salt = Base64.decode(saltStr)
    val iteration = iterationStr.toIntOrNull()
    if (iteration == null || iteration <= 0) {
        throw IOException("invalid SCRAM iteration count received from server: $iterationStr")
    }

    if (!clientAndServerNotice.startsWith(clientNonce)) {
        throw IOException("invalid SCRAM nonce: did not start with client nonce")
    }

    if (clientAndServerNotice.length <= clientNonce.length) {
        throw IOException("invalid SCRAM nonce: did not include server nonce")
    }

    // write client final message
    var channelBindInput = clientGS2Header.encodeToByteArray()
    if (selectMechanism == SCRAM_SHA256_PLUS) {
        channelBindInput += bindingData!!
    }
    val channelBindingEncoded = Base64.encode(channelBindInput)
    val clientFinalMessageWithoutProof = "c=$channelBindingEncoded,r=$clientAndServerNotice"

    val saltedPassword = pbkdf2HmacSha256(password.encodeToByteArray(), salt, iteration)
    val clientKey = hmacSha256(saltedPassword, "Client Key".encodeToByteArray())
    val storedKey = sha256(clientKey)
    val authMessage = "$clientFirstMessageBare,$serverFirstMessage,$clientFinalMessageWithoutProof"
    val clientSignature = hmacSha256(storedKey, authMessage.encodeToByteArray())
    val clientProof = ByteArray(clientSignature.size) { index ->
        clientKey[index] xor clientSignature[index]
    }
    val clientProofEncoded = Base64.encode(clientProof)
    val clientFinalMessage = "$clientFinalMessageWithoutProof,p=${clientProofEncoded}"
    sink.writeSASLResponse(clientFinalMessage.encodeToByteArray())
    sink.flush()

    // receive server final message
    val saslFinal = waitAuthenticationFinal()
    val saslFinalMessage = saslFinal.data.decodeToString()
    val serverFinalParts = saslFinalMessage.split(',')
    val serverSignatureEncoded = serverFinalParts.firstOrNull { it.startsWith("v=") }
        ?.removePrefix("v=")
        ?: throw IOException("invalid SCRAM server-final-message received from server")
    val actualServerSignatureDecoded = Base64.decode(serverSignatureEncoded)
    val serverKey = hmacSha256(saltedPassword, "Server Key".encodeToByteArray(),)
    val actualServerSignature =  hmacSha256(serverKey, authMessage.encodeToByteArray())

    if (!constantTimeEquals(actualServerSignatureDecoded, actualServerSignature)) {
        throw IOException("invalid SCRAM ServerSignature received from server")
    }
}

private suspend fun AsyncConnection.waitAuthenticationSaslContinue(): Message.AuthenticationSaslContinue {
    when (val message = source.readMessage()) {
        is Message.ErrorResponse -> throw buildPgException(message.errors)
        is Message.AuthenticationSaslContinue -> return message
        else -> throw IOException("expected AuthenticationSASLContinue message but received unexpected message $message")
    }
}

private suspend fun AsyncConnection.waitAuthenticationFinal(): Message.AuthenticationSaslFinal {
    when (val message = source.readMessage()) {
        is Message.ErrorResponse -> throw buildPgException(message.errors)
        is Message.AuthenticationSaslFinal -> return message
        else -> throw IOException("expected AuthenticationSaslFinal message but received unexpected message $message")
    }
}

private const val SCRAM_SHA256 = "SCRAM-SHA-256"
private const val SCRAM_SHA256_PLUS = "SCRAM-SHA-256-PLUS"

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
