package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A completed anonymous stage check, never a certificate that the whole source is readable. */
@Serializable
data class SourceCheckSummary(val revision: String, val accountGeneration: Long, val stage: DiagnosticStage,
    val result: String, val count: Int, val checkedAtMillis: Long, val discoveryPage: Boolean = false)

/** Retains only the latest bounded, non-sensitive summary per imported source. */
@Singleton
class SourceCheckHistory @Inject constructor(@ApplicationContext context: Context) {
    private val snapshot = AtomicFile(File(context.filesDir, "source-checks.json"))
    private val lock = Mutex()
    private var restored = false
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<Map<String, SourceCheckSummary>>(emptyMap())
    val results = mutable.asStateFlow()

    suspend fun restore() = withContext(Dispatchers.IO) { lock.withLock {
        if (restored) return@withLock
        mutable.value = try {
            snapshot.openRead().use { input ->
                require(input.channel.size() <= 512 * 1024)
                json.decodeFromString<Map<String, SourceCheckSummary>>(input.readBytes().toString(Charsets.UTF_8))
                    .also { require(it.size <= 1024) }
            }
        } catch (_: Exception) { emptyMap() }
        restored = true
    } }

    suspend fun record(report: SourceDiagnosticReport, discoveryPage: Boolean) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val summary = SourceCheckSummary(report.revision, report.accountGeneration, report.stage,
                report.result, report.count, System.currentTimeMillis(), discoveryPage)
            val next = (mutable.value + (report.sourceId to summary)).entries
                .sortedByDescending { it.value.checkedAtMillis }.take(1024).associate { it.toPair() }
            val output = snapshot.startWrite()
            try {
                output.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                snapshot.finishWrite(output)
            } catch (failure: Exception) { snapshot.failWrite(output); throw failure }
            mutable.value = next
        }
    }
}
