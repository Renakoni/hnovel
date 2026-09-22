package indi.renakoni.nextvol.sourcebrowser

import android.net.http.SslCertificate
import android.net.http.SslError
import hnovel.network.CertificateIssue
import hnovel.network.CertificateProblem
import hnovel.network.sourceOrigin
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** The HTTP and native browser paths both retain normal hostname validation. */
internal fun nativeCertificateProblem(error: SslError): CertificateProblem? = runCatching {
    if (error.hasError(SslError.SSL_IDMISMATCH)) return null
    val origin = sourceOrigin(error.url) ?: return null
    val encoded = SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate") ?: return null
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    CertificateProblem.from(origin, certificate, when {
        error.hasError(SslError.SSL_EXPIRED) -> CertificateIssue.Expired
        error.hasError(SslError.SSL_NOTYETVALID) -> CertificateIssue.NotYetValid
        else -> CertificateIssue.Untrusted
    })
}.getOrNull()
