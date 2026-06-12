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
import openssl.BIO
import openssl.BIO_CTRL_PENDING
import openssl.BIO_new
import openssl.BIO_s_mem
import openssl.BIO_write
import openssl.ERR_error_string_n
import openssl.ERR_get_error
import openssl.SSL_CTX
import openssl.SSL_CTX_new
import openssl.SSL_new
import openssl.SSL_set_bio
import openssl.SSL_set_connect_state
import openssl.TLS_client_method

fun AsyncRawConnection.withTls(
    host: String,
): AsyncConnection = InternalSslConnection(host, this)

internal class InternalSslConnection(
    host: String,
    rawConnection: AsyncRawConnection
) : AsyncConnection {
    private val bufferedConnection = rawConnection.buffered()

    private val ctx: CPointer<SSL_CTX> = SSL_CTX_new(TLS_client_method())
        ?: error("SSL_CTX_new failed")

    private val ssl = SSL_new(ctx)
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

            val ret = openssl.SSL_ctrl(
                ssl,
                openssl.SSL_CTRL_SET_TLSEXT_HOSTNAME,
                openssl.TLSEXT_NAMETYPE_host_name.toLong(),
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
        doHandshake()
        handshakeDone = true
    }

    suspend fun doHandshake() {
        val outputChunk = ByteArray(CHUNK_SIZE)
        while (openssl.SSL_is_init_finished(ssl) == 0) {
            val ret = openssl.SSL_do_handshake(ssl)
            if (ret != 1) {
                val err = openssl.SSL_get_error(ssl, ret)
                if (err == openssl.SSL_ERROR_WANT_READ || err == openssl.SSL_ERROR_WANT_WRITE) {
                    // ignore
                } else {
                    throw IOException(opensslErrorString())
                }
            }

            flushWbioToSink(wbio, bufferedConnection.sink, outputChunk)
            bufferedConnection.sink.flush()

            if (openssl.SSL_is_init_finished(ssl) == 0) {
                feedRbioFromSource(rbio, bufferedConnection.source)
            }
        }
    }

    override suspend fun close() {
        openssl.SSL_shutdown(ssl)
        openssl.SSL_free(ssl)
        openssl.SSL_CTX_free(ctx)

        bufferedConnection.close()
    }
}

internal suspend fun flushWbioToSink(
    wbio: CPointer<BIO>,
    target: AsyncSink,
    outputChunk: ByteArray,
) {
    while (openssl.BIO_ctrl(wbio, BIO_CTRL_PENDING, 0, null) > 0) {
        val n = outputChunk.asUByteArray().usePinned { pinnedOutput ->
            openssl.BIO_read(wbio, pinnedOutput.addressOf(0), outputChunk.size)
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

private fun AsyncRawSource.withReadHook(onRead: suspend () -> Unit): AsyncRawSource =
    ReadHookAsyncSource(onRead, this)

private fun AsyncRawSink.withWriteHook(onWrite: suspend () -> Unit): AsyncRawSink =
    WriteHookAsyncSink(onWrite, this)

private class ReadHookAsyncSource(
    private val onRead: suspend () -> Unit,
    private val delegate: AsyncRawSource
): AsyncRawSource by delegate {

    override suspend fun asyncReadAtMostTo(sink: Buffer, byteCount: Long): Long {
        onRead()
        return delegate.asyncReadAtMostTo(sink, byteCount)
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
