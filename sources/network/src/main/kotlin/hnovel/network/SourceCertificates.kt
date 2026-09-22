package hnovel.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

@Serializable enum class CertificateIssue { Expired, NotYetValid, Untrusted }

/** Public certificate data only. A problem never contains a request path or credentials. */
@Serializable data class CertificateProblem(val origin: String, val certificate: String, val issue: CertificateIssue) {
    init {
        require(sourceOrigin(origin) == origin && origin.startsWith("https://"))
        require(certificate.length in 1..65536)
    }
    fun decode(): X509Certificate {
        val input = Base64.getDecoder().decode(certificate).inputStream()
        val value = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        require(input.available() == 0)
        return value
    }
    val fingerprint: String get() = certificateFingerprint(decode())
    override fun toString() = "CertificateProblem(origin=$origin, issue=$issue)"

    companion object {
        fun from(origin: String, certificate: X509Certificate, issue: CertificateIssue) =
            CertificateProblem(origin, Base64.getEncoder().encodeToString(certificate.encoded), issue)
    }
}

@Serializable data class CertificateExceptionSite(val origin: String, val fingerprint: String) {
    init {
        require(sourceOrigin(origin) == origin && origin.startsWith("https://"))
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

private fun certificateFingerprint(certificate: X509Certificate) =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }

internal class RejectedCertificate(val problem: CertificateProblem, cause: CertificateException) :
    CertificateException("Website certificate rejected", cause)

/** Host-only, account-scoped storage. No script storage area can read or write these grants. */
internal class SourceCertificates(private val storage: SourceStorage, private val systemTrust: X509TrustManager = defaultTrust) {
    private val pending = linkedMapOf<String, CertificateProblem>()

    private fun saved(): Map<String, CertificateProblem> {
        val result = storage.read("sites") as? StorageResult.Value ?: error("Certificate settings unavailable")
        return result.value?.let { Json.decodeFromString<Map<String, CertificateProblem>>(it) }.orEmpty().also { entries ->
            check(entries.size <= 32 && entries.all { (origin, problem) -> origin == problem.origin })
        }
    }

    fun exceptions() = saved().values.map { CertificateExceptionSite(it.origin, it.fingerprint) }

    fun remember(problem: CertificateProblem) {
        problem.decode()
        if (pending.size < 32 || problem.origin in pending) pending[problem.origin] = problem
    }

    fun approve(problem: CertificateProblem) {
        check(pending[problem.origin] == problem) { "Certificate confirmation is stale" }
        val sites = saved() + (problem.origin to problem)
        check(sites.size <= 32)
        check(storage.write("sites", Json.encodeToString(sites)) is StorageResult.Value)
        pending.remove(problem.origin)
    }

    fun revoke(origin: String) {
        check(storage.write("sites", Json.encodeToString(saved() - origin)) is StorageResult.Value)
        pending.remove(origin)
    }

    fun clear() = storage.clear()
    fun close() { pending.clear() }

    fun configure(builder: OkHttpClient.Builder, origin: String): OkHttpClient.Builder {
        val approved = saved()[origin]?.decode()
        val manager = object : X509ExtendedTrustManager() {
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systemTrust.acceptedIssuers + listOfNotNull(approved)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                systemTrust.checkClientTrusted(chain, authType)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
                if (systemTrust is X509ExtendedTrustManager) systemTrust.checkClientTrusted(chain, authType, socket)
                else systemTrust.checkClientTrusted(chain, authType)
            }
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
                if (systemTrust is X509ExtendedTrustManager) systemTrust.checkClientTrusted(chain, authType, engine)
                else systemTrust.checkClientTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
                verify(chain) { systemTrust.checkServerTrusted(chain, authType) }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = verify(chain) {
                // Android's RootTrustManager needs the handshake hostname for network security configuration.
                if (systemTrust is X509ExtendedTrustManager) systemTrust.checkServerTrusted(chain, authType, socket)
                else systemTrust.checkServerTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = verify(chain) {
                if (systemTrust is X509ExtendedTrustManager) systemTrust.checkServerTrusted(chain, authType, engine)
                else systemTrust.checkServerTrusted(chain, authType)
            }
            private fun verify(chain: Array<X509Certificate>, validate: () -> Unit) {
                try { validate() }
                catch (failure: CertificateException) {
                    val leaf = chain.firstOrNull() ?: throw failure
                    // Trust only this leaf at this exact origin. Hostname verification stays enabled.
                    if (approved != null && leaf.encoded.contentEquals(approved.encoded)) return
                    val issue = when {
                        chain.any { it.notAfter.time < System.currentTimeMillis() } -> CertificateIssue.Expired
                        chain.any { it.notBefore.time > System.currentTimeMillis() } -> CertificateIssue.NotYetValid
                        generateSequence<Throwable>(failure) { it.cause }.take(16).any { it is CertificateExpiredException } -> CertificateIssue.Expired
                        generateSequence<Throwable>(failure) { it.cause }.take(16).any { it is CertificateNotYetValidException } -> CertificateIssue.NotYetValid
                        else -> CertificateIssue.Untrusted
                    }
                    throw RejectedCertificate(CertificateProblem.from(origin, leaf, issue), failure)
                }
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return builder.sslSocketFactory(tls.socketFactory, manager)
    }

    companion object {
        private val defaultTrust: X509TrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}
