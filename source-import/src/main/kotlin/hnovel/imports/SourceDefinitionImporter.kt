package hnovel.imports

import hnovel.network.*
import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** Parse/preview/select/commit is deliberately separate from runtime registration and script execution. */
class SourceDefinitionImporter(private val store: SourceDefinitionStore,
    private val limits: ImportLimits = ImportLimits(),
    private val adapters: List<SourceFormatAdapter> = listOf(LegadoSourceAdapter())) {

    fun preview(text: String, profile: String = LEGADO_PROFILE): ImportPreview = parse(text, ImportOrigin(ImportOrigin.Kind.Paste), profile)

    fun previewFile(file: Path, profile: String = LEGADO_PROFILE): ImportPreview {
        return try { Files.newInputStream(file).use { previewStream(it, file.fileName.toString(), profile) } }
        catch (_: Exception) { failure(ImportCode.ReadFailed) }
    }

    /** The host can pass an Android ContentResolver stream. The caller owns and closes the stream. */
    fun previewStream(input: InputStream, displayName: String, profile: String = LEGADO_PROFILE): ImportPreview {
        return try {
            val stream = input.buffered()
            stream.mark(4)
            val magic = ByteArray(4)
            var read = 0
            while (read < magic.size) {
                val count = stream.read(magic, read, magic.size - read)
                if (count < 0) break
                read += count
            }
            stream.reset()
            if (isPlugin(displayName) || magic.contentEquals(byteArrayOf(80, 75, 3, 4))) {
                val json = KnownPluginPackages.read(stream) ?: return failure(ImportCode.PluginPackage)
                // Package mappings own their profile; toggling the JSON profile must not duplicate source identities.
                return parse(json, ImportOrigin(ImportOrigin.Kind.File, displayName), LEGADO_PROFILE,
                    listOf(ImportNotice("KnownPackageAdaptation")))
            }
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, limits.maxBytes - bytes.size() + 1))
                if (count < 0) break
                if (bytes.size() + count > limits.maxBytes) return failure(ImportCode.TooLarge)
                bytes.write(buffer, 0, count)
            }
            parseBytes(bytes.toByteArray(), ImportOrigin(ImportOrigin.Kind.File, displayName), profile)
        } catch (failure: ImportFailure) { failure(failure.code) }
        catch (_: Exception) { failure(ImportCode.ReadFailed) }
    }

    /** The host supplies a dedicated, authorized import session, never a source's login session. */
    suspend fun previewUrl(url: String, session: SourceSession, profile: String = LEGADO_PROFILE): ImportPreview {
        if (isPlugin(url.substringBefore('?').substringBefore('#'))) return failure(ImportCode.PluginPackage)
        return when (val result = session.execute(BrokerRequest("source-import", url, kind = ResourceKind.Import))) {
            is BrokerResult.Failure -> ImportPreview(emptyList(), listOf(ImportIssue(null, ImportCode.DownloadFailed, result.code.name)))
            is BrokerResult.Success -> {
                val response = result.response
                if (response.status !in 200..299) failure(ImportCode.DownloadFailed)
                else if (isPlugin(response.finalUrl.substringBefore('?').substringBefore('#'))) failure(ImportCode.PluginPackage)
                else parseBytes(response.body, ImportOrigin(ImportOrigin.Kind.Url, url, response.finalUrl), profile)
            }
        }
    }

    private fun parseBytes(bytes: ByteArray, origin: ImportOrigin, profile: String): ImportPreview {
        if (bytes.size > limits.maxBytes) return failure(ImportCode.TooLarge)
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) return failure(ImportCode.PluginPackage)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return failure(ImportCode.InvalidJson) }
        return parse(text, origin, profile)
    }

    private fun parse(text: String, origin: ImportOrigin, profile: String, notices: List<ImportNotice> = emptyList()): ImportPreview {
        if (text.length > limits.maxBytes || text.toByteArray(Charsets.UTF_8).size > limits.maxBytes) return failure(ImportCode.TooLarge)
        if (adapters.none { profile in it.profiles }) return failure(ImportCode.UnsupportedProfile)
        val root = try { parseDefinitionJson(text, limits.maxDepth) }
        catch (failure: ImportFailure) { return failure(failure.code) }
        catch (_: Exception) { return failure(ImportCode.InvalidJson) }
        val rows = when (root) {
            is JsonObject -> listOf(root)
            is JsonArray -> root.toList()
            else -> return failure(ImportCode.InvalidShape)
        }
        if (rows.size > limits.maxEntries) return failure(ImportCode.TooMany)
        val saved = try { store.list() } catch (failure: ImportFailure) { return failure(failure.code) }
        val issues = mutableListOf<ImportIssue>()
        val valid = mutableListOf<SourceCandidate>()
        rows.forEachIndexed { index, row ->
            try {
                if (row !is JsonObject) throw ImportFailure(ImportCode.InvalidShape)
                val adapter = adapters.singleOrNull { it.recognizes(row) && profile in it.profiles }
                    ?: throw ImportFailure(ImportCode.UnsupportedFormat)
                val value = adapter.validate(row)
                val existing = saved.firstOrNull { it.profile == profile && it.importKey == value.key }
                valid.add(SourceCandidate(index, adapter.format, profile, value.key, value.name, value.enabled, value.enabledExplore,
                    canonical(row).toString(), origin, notices + value.notices, existing?.reference(),
                    saved.filter { it != existing && (it.importKey == value.key || it.displayName == value.name) }.map { it.reference() }, emptyList()))
            } catch (failure: ImportFailure) { issues.add(ImportIssue(index, failure.code, failure.field)) }
              catch (_: IllegalArgumentException) { issues.add(ImportIssue(index, ImportCode.InvalidField)) }
        }
        val groups = valid.groupBy { it.profile to it.importKey }
        val candidates = valid.map { candidate ->
            SourceCandidate(candidate.index, candidate.format, candidate.profile, candidate.importKey, candidate.displayName,
                candidate.enabled, candidate.enabledExplore, candidate.rawJson, candidate.origin, candidate.notices,
                candidate.existing, candidate.possibleMatches, groups.getValue(candidate.profile to candidate.importKey)
                    .filter { it.index != candidate.index }.map { it.index })
        }
        return ImportPreview(candidates, issues.toList())
    }

    fun commit(preview: ImportPreview, selections: List<ImportSelection>): ImportCommit {
        if (selections.size > limits.maxEntries) return ImportCommit(emptyList(), ImportCode.TooMany)
        if (selections.map { it.index }.toSet().size != selections.size) return ImportCommit(emptyList(), ImportCode.DuplicateSelection)
        return try {
            store.transaction { stored ->
                val indexed = preview.candidates.associateBy { it.index }
                // Reject every conflicting selection; ordering cannot choose a winner implicitly.
                val keyed = selections.groupBy { indexed[it.index]?.let { source -> source.profile to source.importKey } }
                val targeted = selections.groupBy { selection -> when (val decision = selection.decision) {
                    ImportDecision.Add -> indexed[selection.index]?.let { newSourceId(it.profile, it.importKey) }
                    is ImportDecision.Replace -> decision.expected.sourceId
                    is ImportDecision.MapIdentity -> decision.expected.sourceId
                } }
                val results = selections.map { selection ->
                    val source = indexed[selection.index]
                    val targetId = when (val decision = selection.decision) {
                        ImportDecision.Add -> source?.let { newSourceId(it.profile, it.importKey) }
                        is ImportDecision.Replace -> decision.expected.sourceId
                        is ImportDecision.MapIdentity -> decision.expected.sourceId
                    }
                    val duplicate = source != null && (keyed[source.profile to source.importKey].orEmpty().size > 1 || targeted[targetId].orEmpty().size > 1)
                    when {
                        source == null -> ImportItemResult(selection.index, ImportOutcome.Failed, error = ImportCode.InvalidSelection)
                        duplicate -> ImportItemResult(selection.index, ImportOutcome.Failed, error = ImportCode.DuplicateSelection)
                        else -> applySelection(source, selection.decision, stored)
                    }
                }
                ImportCommit(results)
            }
        } catch (failure: ImportFailure) {
            // A failed atomic commit rolls back every success calculated above.
            ImportCommit(selections.map { ImportItemResult(it.index, ImportOutcome.Failed, error = failure.code) }, failure.code)
        }
    }

    private fun applySelection(source: SourceCandidate, decision: ImportDecision, stored: MutableList<SourceDefinition>): ImportItemResult {
        fun rejected(code: ImportCode) = ImportItemResult(source.index, ImportOutcome.Failed, error = code)
        val exact = stored.firstOrNull { it.profile == source.profile && it.importKey == source.importKey }
        val expected = when (decision) {
            ImportDecision.Add -> null
            is ImportDecision.Replace -> decision.expected
            is ImportDecision.MapIdentity -> decision.expected
        }
        val old = expected?.let { ref -> stored.firstOrNull { it.sourceId == ref.sourceId } }
        if (expected != null && (old == null || old.revision != expected.revision)) return rejected(ImportCode.StalePreview)
        if (decision is ImportDecision.Replace && old != exact) return rejected(ImportCode.Conflict)
        if (exact != null && exact != old) return rejected(ImportCode.Conflict)
        val sourceId = old?.sourceId ?: newSourceId(source.profile, source.importKey)
        if (old == null && stored.any { it.sourceId == sourceId }) return rejected(ImportCode.Conflict)
        if (old != null && old.rawJson == source.rawJson && old.profile == source.profile) {
            return ImportItemResult(source.index, ImportOutcome.Unchanged, old.reference())
        }
        val definition = SourceDefinition(sourceId, source.format, source.profile, source.importKey, source.displayName,
            source.enabled, source.enabledExplore, source.origin, digest(source.rawJson), (old?.revision ?: 0) + 1, source.rawJson)
        if (old == null) stored.add(definition) else stored[stored.indexOf(old)] = definition
        return ImportItemResult(source.index, if (old == null) ImportOutcome.Added else ImportOutcome.Replaced, definition.reference())
    }

    private fun isPlugin(name: String) = name.endsWith(".apk", true) || name.endsWith(".lnrp", true)
    private fun failure(code: ImportCode) = ImportPreview(emptyList(), listOf(ImportIssue(null, code)))
}
