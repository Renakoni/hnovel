package indi.renakoni.nextvol.sourcebrowser

import android.os.ParcelFileDescriptor
import hnovel.network.BrokerRequest
import hnovel.network.BrokerResult
import hnovel.network.BrokerLimits
import hnovel.network.BrowserOptions
import hnovel.network.FailureCode
import hnovel.network.LocalStorageRetention
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

@Serializable internal data class BrowserJob(val request: BrokerRequest, val options: BrowserOptions,
    val profile: String = "", val cookiesEnabled: Boolean = true, val networkHandle: Long? = null,
    val certificates: List<hnovel.network.CertificateExceptionSite> = emptyList(),
    val cookies: List<String> = emptyList(), val jobId: String = "", val cookieVersion: Long = 0)

@Serializable internal data class NativeCookieSnapshot(val url: String, val cookies: List<String>, val completeMetadata: Boolean)

internal val BrowserOptions.sharedNativePage get() = !interactive && script.isBlank() && sourceRegex.isBlank() &&
    webCookie == null && !overrideUrl && !verificationCode && html == null

@Serializable private data class BrowserResultMessage(val result: BrokerResult, val body: String? = null)

@Serializable internal data class LocalStorageJob(val profile: String, val selection: LocalStorageRetention,
    val values: Map<String, Map<String, String>>? = null, val networkHandle: Long? = null)

@Serializable internal data class LocalStorageResult(val values: Map<String, Map<String, String>> = emptyMap(),
    val failure: FailureCode? = null)

/** Large response bodies use bounded pipes, never the shared Binder transaction buffer. */
internal object BrowserWire {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_RESULT_BYTES = 24 * 1024 * 1024
    private val writers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun pipe(text: String, maxBytes: Int = MAX_BYTES): ParcelFileDescriptor {
        val bytes = text.toByteArray()
        require(bytes.size <= maxBytes)
        val ends = ParcelFileDescriptor.createPipe()
        writers.launch { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { it.write(bytes) } } }
        return ends[0]
    }

    /** Base64 avoids expanding every response byte into a signed JSON number. */
    fun pipeResult(result: BrokerResult): ParcelFileDescriptor {
        val message = if (result is BrokerResult.Success) {
            require(result.response.body.size <= BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES)
            BrowserResultMessage(result.copy(response = result.response.copy(body = ByteArray(0))),
                android.util.Base64.encodeToString(result.response.body, android.util.Base64.NO_WRAP))
        } else BrowserResultMessage(result)
        return pipe(Json.encodeToString(message), MAX_RESULT_BYTES)
    }

    fun readResult(pipe: ParcelFileDescriptor): BrokerResult {
        val message = Json.decodeFromString<BrowserResultMessage>(read(pipe, MAX_RESULT_BYTES))
        val result = message.result
        return if (result is BrokerResult.Success) {
            require(result.response.body.isEmpty())
            val bytes = android.util.Base64.decode(requireNotNull(message.body), android.util.Base64.NO_WRAP)
            require(bytes.size <= BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES)
            result.copy(response = result.response.copy(body = bytes))
        } else { require(message.body == null); result }
    }

    fun read(pipe: ParcelFileDescriptor, maxBytes: Int = MAX_BYTES): String = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() + count <= maxBytes)
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
}
