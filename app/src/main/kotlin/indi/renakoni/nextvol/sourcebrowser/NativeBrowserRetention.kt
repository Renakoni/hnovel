package indi.renakoni.nextvol.sourcebrowser

import android.util.AtomicFile
import hnovel.network.LocalStorageRetention
import hnovel.network.SourceScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/** A source-owned, bounded handoff. Null values mark an already consumed account generation. */
@Serializable
internal data class RetainedLocalStorage(val generation: Long, val values: Map<String, Map<String, String>>? = null) {
    init {
        require(generation > 0)
        values?.let { LocalStorageRetention(it.mapValues { entry -> entry.value.keys.toList() }).validate(it) }
    }
}

/** Accessed only under NativeSourceBrowser's process/profile mutex. Contains no Cookie or DB copies. */
internal class NativeBrowserRetention(directory: File, scope: SourceScope) {
    private val file = AtomicFile(File(directory, "source-browser-retention/${nativeBrowserProfile(scope.copy(accountGeneration = 0))}.json"))

    fun read(): RetainedLocalStorage? = try {
        file.openRead().use {
            check(it.channel.size() <= MAX_BYTES) { "Retained localStorage exceeds quota" }
            Json.decodeFromString<RetainedLocalStorage>(it.readBytes().toString(Charsets.UTF_8))
        }
    } catch (_: FileNotFoundException) {
        // A directory or unreadable file at this location is an error, not an empty handoff.
        check(!file.baseFile.exists()) { "Retained localStorage is unavailable" }
        null
    }

    fun write(value: RetainedLocalStorage) {
        val bytes = Json.encodeToString(value).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        // AtomicFile reports some rename failures only through Log; do not claim a handoff was committed.
        check(read() == value) { "Retained localStorage was not committed" }
    }

    companion object { const val MAX_BYTES = 512 * 1024 }
}
