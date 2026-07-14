package kio.tls

import kio.async.AsyncRawSink
import kio.async.AsyncRawSource
import kio.async.io.AsyncConnection
import kio.async.io.AsyncRawConnection
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.IOException

interface SslConnection : AsyncConnection {
    @Throws(IOException::class, CancellationException::class)
    suspend fun handShake()

    fun getSelectedAlpn(): String?
}

expect fun AsyncRawConnection.withClientTls(
    host: String,
    alpnProtos: List<String> = listOf(),
): SslConnection

expect fun AsyncRawConnection.withServerTls(
    certificate: CertificateFile,
    privateKeyFile: CertificateFile,
    supportAlpnProtocols: List<String> = listOf(),
): SslConnection

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

internal fun AsyncRawSource.withReadHook(onRead: suspend () -> Unit): AsyncRawSource =
    ReadHookAsyncSource(onRead, this)

internal fun AsyncRawSink.withWriteHook(onWrite: suspend () -> Unit): AsyncRawSink =
    WriteHookAsyncSink(onWrite, this)

private class ReadHookAsyncSource(
    private val onRead: suspend () -> Unit,
    private val delegate: AsyncRawSource
) : AsyncRawSource by delegate {

    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        onRead()
        return delegate.readAtMostTo(sink, byteCount)
    }
}

private class WriteHookAsyncSink(
    private val onWrite: suspend () -> Unit,
    private val delegate: AsyncRawSink
) : AsyncRawSink by delegate {
    override suspend fun write(source: Buffer, byteCount: Long) {
        onWrite()
        delegate.write(source, byteCount)
    }

    override suspend fun flush() {
        onWrite()
        delegate.flush()
    }
}
