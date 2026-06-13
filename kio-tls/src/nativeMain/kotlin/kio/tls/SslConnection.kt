@file:OptIn(ExperimentalForeignApi::class)

package kio.tls

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered
import kio.network.AsyncConnection
import kio.network.AsyncRawConnection
import kio.network.buffered
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.unsafe.withData
import openssl.*

fun AsyncRawConnection.withClientTls(
    host: String,
): AsyncConnection = InternalSslClientConnection(host, this)

fun AsyncRawConnection.withServerTls(
    certificate: CertificateFile,
    privateKeyFile: CertificateFile,
): AsyncConnection = InternalSslServerConnection(certificate, privateKeyFile, this)

data class CertificateFile(
    val file: String,
    val type: CertificateFileType,
)

val String.pem get() = CertificateFile(this, CertificateFileType.PEM)
val String.asn1 get() = CertificateFile(this, CertificateFileType.ASN1)

enum class CertificateFileType {
    PEM,
    ASN1
}

internal class InternalSslClientConnection(
    host: String,
    rawConnection: AsyncRawConnection
) : AsyncConnection {
    private val bufferedConnection = rawConnection.buffered()

    private val ctx: CPointer<SSL_CTX> = SSL_CTX_new(TLS_client_method())
        ?: error("SSL_CTX_new failed")

    private val ssl: CPointer<SSL> = SSL_new(ctx)
        ?: error("SSL_new failed")

    private val rbio: CPointer<BIO> = BIO_new(BIO_s_mem())
        ?: error("BIO_new failed")
    private val wbio: CPointer<BIO> = BIO_new(BIO_s_mem())
        ?: error("BIO_new failed")

    init {
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
}

internal class InternalSslServerConnection(
    certificate: CertificateFile,
    privateKeyFile: CertificateFile,
    rawConnection: AsyncRawConnection,
): AsyncConnection {
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
}

internal suspend fun doHandshake(ssl: CPointer<SSL>, wbio: CPointer<BIO>, rbio: CPointer<BIO>, bufferedConnection: AsyncConnection) {
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

private fun AsyncRawSource.withReadHook(onRead: suspend () -> Unit): AsyncRawSource =
    ReadHookAsyncSource(onRead, this)

private fun AsyncRawSink.withWriteHook(onWrite: suspend () -> Unit): AsyncRawSink =
    WriteHookAsyncSink(onWrite, this)

private class ReadHookAsyncSource(
    private val onRead: suspend () -> Unit,
    private val delegate: AsyncRawSource
): AsyncRawSource by delegate {

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        onRead()
        return delegate.readAtMostTo(sink, byteCount)
    }
}

private class WriteHookAsyncSink(
    private val onWrite: suspend () -> Unit,
    private val delegate: AsyncRawSink
): AsyncRawSink by delegate {
    override suspend fun write(source: Buffer, byteCount: Long) {
        onWrite()
        delegate.write(source, byteCount)
    }

    override suspend fun flush() {
        onWrite()
        delegate.flush()
    }
}
