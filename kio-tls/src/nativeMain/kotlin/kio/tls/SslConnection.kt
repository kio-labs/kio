@file:OptIn(ExperimentalForeignApi::class)

package kio.tls

import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered
import kio.async.io.AsyncConnection
import kio.async.io.AsyncRawConnection
import kio.async.io.buffered
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.UnsafeIoApi
import kotlinx.io.readByteArray
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.unsafe.withData
import kotlinx.io.writeString
import openssl.*

actual fun AsyncRawConnection.withClientTls(
    host: String,
    alpnProtos: List<String>,
): SslConnection = InternalSslClientConnection(host, alpnProtos, this)

actual fun AsyncRawConnection.withServerTls(
    certificate: CertificateFile,
    privateKeyFile: CertificateFile,
): AsyncConnection = InternalSslServerConnection(certificate, privateKeyFile, this)

internal class InternalSslClientConnection(
    host: String,
    alpnProtos: List<String>,
    rawConnection: AsyncRawConnection
) : SslConnection {
    private val bufferedConnection = rawConnection.buffered()
    private val ctx: CPointer<SSL_CTX> =
        SSL_CTX_new(TLS_client_method()) ?: error("SSL_CTX_new failed")

    init {
        SSL_CTX_set_info_callback(ctx, getSslInfoCallBack())
    }

    private val ssl: CPointer<SSL> = SSL_new(ctx) ?: error("SSL_new failed")

    private val rbio: CPointer<BIO> = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")
    private val wbio: CPointer<BIO> = BIO_new(BIO_s_mem()) ?: error("BIO_new failed")

    private var handshakeDone = false

    init {
        if (alpnProtos.isNotEmpty() && setAlpnProtos(ssl, alpnProtos) != 0) {
            throw IOException("SSL_set_alpn_protos failed.")
        }
        println("client ALPN configured: " + listOf(alpnProtos).joinToString())

        SSL_set_bio(ssl, rbio, wbio)
        SSL_set_connect_state(ssl)
        // SSL_set_tlsext_host_name
        memScoped {
            val hostPtr = host.cstr.ptr

            val ret = SSL_ctrl(
                ssl,
                SSL_CTRL_SET_TLSEXT_HOSTNAME,
                TLSEXT_NAMETYPE_host_name.toLong(),
                hostPtr
            )

            if (ret != 1L) {
                throw IOException(opensslErrorString())
            }
        }
    }

    override val source = SslSource(
        rbio = rbio,
        ssl = ssl,
        source = bufferedConnection.source,
    ).withReadHook(::doHandshakeIfNeeded).buffered()

    override val sink = SslSink(
        wbio = wbio,
        ssl = ssl,
        sink = bufferedConnection.sink,
    ).withWriteHook(::doHandshakeIfNeeded).buffered()

    override fun getSelectedAlpn(): String? {
        return getSelectedAlpn(ssl)
    }

    override suspend fun handShake() {
        doHandshakeIfNeeded()
    }

    override suspend fun close() {
        SSL_shutdown(ssl)
        SSL_free(ssl)
        SSL_CTX_free(ctx)

        bufferedConnection.close()
    }

    private suspend fun doHandshakeIfNeeded() {
        if (handshakeDone) return
        doHandshake(ssl, wbio, rbio, bufferedConnection)
        handshakeDone = true
    }

    private fun setAlpnProtos(ssl: CPointer<SSL>, alpnProtos: List<String>): Int {
        val b = Buffer()
        alpnProtos.forEach { proto ->
            b.writeByte(proto.length.toByte())
            b.writeString(proto)
        }
        val byteArray = b.readByteArray().asUByteArray()
        return byteArray.usePinned { pinned ->
            SSL_set_alpn_protos(ssl, pinned.addressOf(0), byteArray.size.toUInt())
        }
    }
}

internal class InternalSslServerConnection(
    certificate: CertificateFile,
    privateKeyFile: CertificateFile,
    rawConnection: AsyncRawConnection,
) : AsyncConnection {
    private val bufferedConnection = rawConnection.buffered()

    private val ctx: CPointer<SSL_CTX> = SSL_CTX_new(TLS_server_method())
        ?: error("SSL_CTX_new failed")

    init {
        if (SSL_CTX_use_certificate_file(ctx, certificate.file, certificate.type.toType()) != 1) {
            throw IOException(opensslErrorString())
        }

        if (SSL_CTX_use_PrivateKey_file(ctx, privateKeyFile.file, privateKeyFile.type.toType()) != 1) {
            throw IOException(opensslErrorString())
        }
        SSL_CTX_set_alpn_select_cb(ctx, getAlpnSelectCallBack(), null)
        SSL_CTX_set_info_callback(ctx, getSslInfoCallBack())
    }

    private val ssl = SSL_new(ctx)
        ?: error("SSL_new failed")

    private val rbio: CPointer<BIO> = BIO_new(BIO_s_mem())
        ?: error("BIO_new failed")
    private val wbio: CPointer<BIO> = BIO_new(BIO_s_mem())
        ?: error("BIO_new failed")

    init {
        SSL_set_bio(ssl, rbio, wbio)
        SSL_set_accept_state(ssl)
    }

    private var handshakeDone = false

    override val source = SslSource(
        rbio = rbio,
        ssl = ssl,
        source = bufferedConnection.source,
    ).withReadHook(::doHandshakeIfNeeded).buffered()

    override val sink = SslSink(
        wbio = wbio,
        ssl = ssl,
        sink = bufferedConnection.sink,
    ).withWriteHook(::doHandshakeIfNeeded).buffered()

    private suspend fun doHandshakeIfNeeded() {
        if (handshakeDone) return
        doHandshake(ssl, wbio, rbio, bufferedConnection)
        handshakeDone = true
    }

    override suspend fun close() {
        SSL_shutdown(ssl)
        SSL_free(ssl)
        SSL_CTX_free(ctx)

        bufferedConnection.close()
    }

    private fun getAlpnSelectCallBack():
            CPointer<CFunction<(
                CPointer<SSL>?,
                CPointer<CPointerVar<UByteVar>>?,
                CPointer<UByteVar>?,
                CPointer<UByteVar>?,
                UInt,
                COpaquePointer?
            ) -> Int>> =
        staticCFunction {
                ssl,
                out,
                outLen,
                clientProtocols,
                clientProtocolsLength,
                arg,
            ->
            if (ssl == null || out == null || outLen == null || clientProtocols == null) return@staticCFunction SSL_TLSEXT_ERR_ALERT_FATAL

// TODO("implement alpn select")
            SSL_TLSEXT_ERR_NOACK
        }
}

internal suspend fun doHandshake(
    ssl: CPointer<SSL>,
    wbio: CPointer<BIO>,
    rbio: CPointer<BIO>,
    bufferedConnection: AsyncConnection
) {
    val outputChunk = ByteArray(CHUNK_SIZE)
    while (SSL_is_init_finished(ssl) == 0) {
        val ret = SSL_do_handshake(ssl)
        if (ret != 1) {
            val err = SSL_get_error(ssl, ret)
            if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE) {
                // ignore
            } else {
                throw IOException(opensslErrorString())
            }
        }

        flushWbioToSink(wbio, bufferedConnection.sink, outputChunk)
        bufferedConnection.sink.flush()

        if (SSL_is_init_finished(ssl) == 0) {
            feedRbioFromSource(rbio, bufferedConnection.source)
        }
    }
}

private fun getSelectedAlpn(ssl: CPointer<SSL>): String? = memScoped {
    val aPtr = alloc<CPointerVar<UByteVar>>()
    val length = alloc<UIntVar>()
    SSL_get0_alpn_selected(ssl, aPtr.ptr, length.ptr)
    val pointer = aPtr.value
    val size = length.value.toInt()
    if (pointer == null || size == 0) {
        null
    } else {
        pointer.readBytes(size).decodeToString()
    }
}

private fun getSslInfoCallBack(): CPointer<CFunction<(CPointer<SSL>?, Int, Int) -> Unit>> =
    staticCFunction { ssl, type, v ->
        if (ssl == null) return@staticCFunction

        val state = SSL_state_string_long(ssl)?.toKString() ?: return@staticCFunction
        when {
            type and SSL_CB_HANDSHAKE_START != 0 -> {
                println("[TLS] handshake start")
            }

            type and SSL_CB_HANDSHAKE_DONE != 0 -> {
                val version = SSL_get_version(ssl)?.toKString()

                val cipher = SSL_CIPHER_get_name(SSL_get_current_cipher(ssl))?.toKString()
                println("[TLS] handshake done: version=$version, cipher=$cipher")
            }

            type and SSL_CB_LOOP != 0 -> {
                println("[TLS] state: $state")
            }

            type and SSL_CB_ALERT != 0 -> {
                val direction = if (type and SSL_CB_READ != 0) "received" else "sent"
                val alertType = SSL_alert_type_string_long(v)?.toKString()

                val alertDescription = SSL_alert_desc_string_long(v)?.toKString()

                println("[TLS] alert $direction: $alertType, $alertDescription")
            }

            type and SSL_CB_EXIT != 0 && v <= 0 -> {
                println("[TLS] exit: state=$state, ret=$v")
            }
        }
    }

internal suspend fun flushWbioToSink(
    wbio: CPointer<BIO>,
    target: AsyncSink,
    outputChunk: ByteArray,
) {
    while (BIO_ctrl(wbio, BIO_CTRL_PENDING, 0, null) > 0) {
        val n = outputChunk.asUByteArray().usePinned { pinnedOutput ->
            BIO_read(wbio, pinnedOutput.addressOf(0), outputChunk.size)
        }

        if (n <= 0) break

        target.write(outputChunk, 0, n)
    }
}

@OptIn(UnsafeIoApi::class, InternalIoApi::class)
internal suspend fun feedRbioFromSource(
    rbio: CPointer<BIO>,
    source: AsyncSource
): Boolean {
    if (source.exhausted()) return false

    var consumed = 0

    UnsafeBufferOperations.readFromHead(source.buffer) { readContext, segment ->
        readContext.withData(segment) { inputArray, pos, limit ->
            inputArray.asUByteArray().usePinned { pinnedInput ->
                val len = limit - pos
                val written = BIO_write(rbio, pinnedInput.addressOf(pos), len)

                if (written <= 0) {
                    throw IOException(opensslErrorString())
                }

                consumed = written
            }
        }

        consumed
    }

    return consumed > 0
}

@OptIn(ExperimentalForeignApi::class)
internal fun opensslErrorString(): String {
    val errors = mutableListOf<String>()

    while (true) {
        val code = ERR_get_error()
        if (code == 0uL) break

        val buf = ByteArray(256)
        val message = buf.usePinned { pinned ->
            ERR_error_string_n(code, pinned.addressOf(0), buf.size.convert())
            pinned.addressOf(0).toKString()
        }

        errors += message
    }

    return errors.joinToString("\n").ifEmpty {
        "No OpenSSL error in queue"
    }
}

private fun CertificateFileType.toType() = when (this) {
    CertificateFileType.PEM -> SSL_FILETYPE_PEM
    CertificateFileType.ASN1 -> SSL_FILETYPE_ASN1
}
