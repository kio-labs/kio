package kio.tls

import kio.async.io.AsyncConnection
import kio.async.io.AsyncRawConnection

actual fun AsyncRawConnection.withClientTls(host: String): kio.async.io.AsyncConnection {
    TODO("Not yet implemented")
}

actual fun AsyncRawConnection.withServerTls(
    certificate: CertificateFile,
    privateKeyFile: CertificateFile
): AsyncConnection {
    TODO("Not yet implemented")
}