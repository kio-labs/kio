@file:OptIn(ExperimentalForeignApi::class)

package kio.tls

import kio.async.AsyncSink
import kio.async.AsyncSource
import kio.async.buffered
import kio.network.AsyncConnection
import kio.network.AsyncRawConnection
import kio.network.buffered
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import openssl.BIO_new
import openssl.BIO_s_mem
import openssl.SSL_CTX
import openssl.SSL_CTX_new
import openssl.SSL_new
import openssl.SSL_set_bio
import openssl.SSL_set_connect_state
import openssl.TLS_client_method

fun AsyncRawConnection.tlsClient(): AsyncConnection = InternalSslConnection(this)

internal class InternalSslConnection(
    connection: AsyncRawConnection
) : AsyncConnection {
    private val bufferedConnection =
        connection as? AsyncConnection ?: connection.buffered()

    private val ctx: CPointer<SSL_CTX> = SSL_CTX_new(TLS_client_method())
        ?: error("")

    private val ssl = SSL_new(ctx)
        ?: error("")

    private val rbio = BIO_new(BIO_s_mem())
        ?: error("")
    private val wbio = BIO_new(BIO_s_mem())
        ?: error("")

    init {
        SSL_set_bio(ssl, rbio, wbio)
        SSL_set_connect_state(ssl)
    }

    override val source: AsyncSource = SslSource(
        rbio = rbio,
        ssl = ssl,
        source = bufferedConnection.source,
    ).buffered()

    override val sink: AsyncSink = SslSink(
        wbio = wbio,
        ssl = ssl,
        sink = bufferedConnection.sink,
    ).buffered()

    override suspend fun close() {
        TODO("Not yet implemented")
    }
}