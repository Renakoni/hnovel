package hnovel.imports

import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest

@kotlinx.serialization.Serializable
data class SourceDefinition(
    val sourceId: String, val profile: String, val displayName: String,
    val importKey: String, val enabled: Boolean, val enabledExplore: Boolean,
    val raw: JsonObject
)
data class ImportIssue(val index: Int?, val code: String, val message: String)
data class ImportPreview(val definitions: List<SourceDefinition>, val issues: List<ImportIssue>)

class SourceDefinitionImporter(private val maxBytes: Int = 2 * 1024 * 1024, private val maxEntries: Int = 256) {
    fun preview(text: String, profile: String = "legado"): ImportPreview {
        if (text.toByteArray().size > maxBytes) return ImportPreview(emptyList(), listOf(ImportIssue(null,"too_large","source exceeds size limit")))
        val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { return ImportPreview(emptyList(), listOf(ImportIssue(null,"invalid_json","source is not valid JSON"))) }
        val values = when (root) { is JsonObject -> listOf(root); is JsonArray -> root.mapNotNull { it as? JsonObject }; else -> emptyList() }
        if (root !is JsonObject && root !is JsonArray) return ImportPreview(emptyList(), listOf(ImportIssue(null, "invalid_shape", "source must be an object or array")))
        if (values.size > maxEntries) return ImportPreview(emptyList(), listOf(ImportIssue(null,"too_many","source count exceeds limit")))
        val definitions = mutableListOf<SourceDefinition>(); val issues = mutableListOf<ImportIssue>()
        values.forEachIndexed { index, obj ->
            val key = obj["bookSourceUrl"]?.jsonPrimitive?.contentOrNull
            if (key.isNullOrBlank()) { issues += ImportIssue(index,"missing_identity","bookSourceUrl is required"); return@forEachIndexed }
            val name = obj["bookSourceName"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: key
            val id = stableId(profile, key)
            definitions += SourceDefinition(id, profile, name, key, obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                obj["enabledExplore"]?.jsonPrimitive?.booleanOrNull ?: true, obj)
        }
        return ImportPreview(definitions, issues)
    }
    fun import(text: String, target: Path, profile: String = "legado"): ImportPreview {
        val preview = preview(text, profile); if (preview.issues.isNotEmpty() || preview.definitions.isEmpty()) return preview
        Files.createDirectories(target); preview.definitions.forEach { definition ->
            val path = target.resolve(hash(definition.sourceId) + ".json"); val temp = Files.createTempFile(target,"source-",".tmp")
            try { Files.writeString(temp, Json.encodeToString(JsonObject.serializer(), definition.raw)); Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            finally { Files.deleteIfExists(temp) }
        }; return preview
    }
    companion object { fun stableId(profile: String, key: String) = hash("${profile.length}:$profile${key.length}:$key")
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) } }
}
