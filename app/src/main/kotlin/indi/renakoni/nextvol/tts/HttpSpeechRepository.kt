package indi.renakoni.nextvol.tts

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.execution.BridgeWire
import hnovel.execution.ExecutionAuthority
import hnovel.network.StorageCipher
import hnovel.network.sourceOrigin
import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.previewHttpSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SavedHttpSpeechSource(val definition: HttpSpeechDefinition, val origins: List<String>)

/** User-imported definitions can contain credentials. Persist them with the existing Keystore cipher. */
@Singleton
class HttpSpeechRepository @Inject constructor(@ApplicationContext context: Context, private val cipher: StorageCipher,
    private val authority: ExecutionAuthority) {
    private val file = AtomicFile(File(context.filesDir, "http-speech.enc"))
    private val runtimeRoot = File(context.filesDir, "speech-runtime")
    private val mutex = Mutex()
    private val mutableDeniedOrigins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val deniedOrigins = mutableDeniedOrigins.asStateFlow()

    internal fun recordDeniedOrigins(id: String, origins: List<String>) {
        val safe = origins.filter { sourceOrigin(it) == it }.distinct().take(32)
        if (safe.isNotEmpty()) mutableDeniedOrigins.update { (it + (id to safe)).entries.toList().takeLast(512).associate { entry -> entry.toPair() } }
    }

    suspend fun sources(): List<SavedHttpSpeechSource> = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    /** Serialize opening with replacement/removal so an old definition cannot issue a new ticket afterward. */
    internal suspend fun <T> withSource(id: String, open: (SavedHttpSpeechSource, File) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock { read().find { it.definition.id == id }?.let { open(it, runtimeDirectory(id)) } }
    }

    private fun runtimeDirectory(id: String) = File(runtimeRoot, speechDigest(id))

    suspend fun save(sources: List<SavedHttpSpeechSource>) = change { current ->
        val incoming = sources.associateBy { it.definition.id }
        current.map { incoming[it.definition.id] ?: it } + sources.filter { item -> current.none { it.definition.id == item.definition.id } }
    }

    suspend fun delete(id: String) = change { it.filterNot { source -> source.definition.id == id } }

    private suspend fun change(update: (List<SavedHttpSpeechSource>) -> List<SavedHttpSpeechSource>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read()
            val sources = update(previous)
            require(sources.size <= 512 && sources.map { it.definition.id }.distinct().size == sources.size)
            val raw = JsonArray(sources.map { source ->
                require(source.origins.size <= 32 && source.origins.all { sourceOrigin(it) == it })
                buildJsonObject { put("definition", source.definition.raw); put("origins", JsonArray(source.origins.map(::JsonPrimitive))) }
            }).toString().toByteArray(Charsets.UTF_8)
            require(raw.size <= MAX_BYTES)
            BridgeWire.validate(raw, MAX_BYTES)
            val encrypted = cipher.seal(raw, IDENTITY)
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
            previous.filter { old -> sources.none { it.definition.id == old.definition.id &&
                it.definition.revision == old.definition.revision && it.origins == old.origins } }.forEach { old ->
                authority.revokeSource(old.definition.id, "speech")
                val runtime = runtimeDirectory(old.definition.id)
                if (runtime.exists() && !runtime.deleteRecursively()) throw java.io.IOException("Speech runtime cleanup failed")
            }
            mutableDeniedOrigins.update { denied -> denied.mapNotNull { (id, origins) ->
                sources.find { it.definition.id == id }?.let { source -> id to (origins - source.origins.toSet()) }
            }.toMap() }
        }
    }

    private fun read(): List<SavedHttpSpeechSource> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val encrypted = file.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= MAX_BYTES + 512)
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        val raw = cipher.open(encrypted, IDENTITY)
        val entries = Json.parseToJsonElement(BridgeWire.validate(raw, MAX_BYTES)).jsonArray
        require(entries.size <= 512)
        val preview = previewHttpSpeech(JsonArray(entries.map { it.jsonObject.getValue("definition") }).toString())
        require(preview.issues.isEmpty() && preview.sources.size == entries.size)
        return preview.sources.zip(entries) { source, entry ->
            val origins = entry.jsonObject.getValue("origins").jsonArray.map { it.jsonPrimitive.content }
            require(origins.size <= 32 && origins.all { sourceOrigin(it) == it })
            SavedHttpSpeechSource(source, origins)
        }
    }

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024; private const val IDENTITY = "http-speech-definitions-v1" }
}
