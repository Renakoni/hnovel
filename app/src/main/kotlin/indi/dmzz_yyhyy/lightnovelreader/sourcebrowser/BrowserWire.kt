package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.os.ParcelFileDescriptor
import hnovel.network.BrokerRequest
import hnovel.network.BrowserOptions
import hnovel.network.FailureCode
import hnovel.network.LocalStorageRetention
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream

@Serializable internal data class BrowserJob(val request: BrokerRequest, val options: BrowserOptions,
    val profile: String = "", val cookiesEnabled: Boolean = true, val networkHandle: Long? = null)

@Serializable internal data class LocalStorageJob(val profile: String, val selection: LocalStorageRetention,
    val values: Map<String, Map<String, String>>? = null, val networkHandle: Long? = null)

@Serializable internal data class LocalStorageResult(val values: Map<String, Map<String, String>> = emptyMap(),
    val failure: FailureCode? = null)

/** Large response bodies use bounded pipes, never the shared Binder transaction buffer. */
internal object BrowserWire {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val writers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun pipe(text: String): ParcelFileDescriptor {
        val bytes = text.toByteArray()
        require(bytes.size <= MAX_BYTES)
        val ends = ParcelFileDescriptor.createPipe()
        writers.launch { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { it.write(bytes) } } }
        return ends[0]
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
