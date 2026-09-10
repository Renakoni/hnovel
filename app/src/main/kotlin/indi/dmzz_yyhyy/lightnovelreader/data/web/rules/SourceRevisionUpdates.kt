package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleSource
import hnovel.content.RuleTaskRunner
import hnovel.execution.*
import hnovel.imports.*
import hnovel.network.*
import hnovel.rules.OutputKind
import hnovel.rules.RuleValue
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RevisionError { NotInstalled, NoUpdateAddress, InvalidCandidate, IdentityChanged, ProfileChanged,
    PermissionRequired, InitializationFailed, Stale, NoPreviousRevision }
class RevisionException(val code: RevisionError) : Exception(code.name)
data class RevisionCheck(val installed: SourceDefinition, val preview: ImportPreview, val unchanged: Boolean)

/** Checks never execute source code. Apply validates in disposable storage before one durable activation. */
@Singleton
class SourceRevisionUpdates @Inject constructor(@ApplicationContext context: Context,
    private val sources: ImportedRuleSources, private val accounts: SourceSessionManager,
    private val runner: RuleTaskRunner, private val authority: ExecutionAuthority) {
    private val temporary = File(context.cacheDir, "source-revision-checks")

    suspend fun check(source: Identifier, downloadGrant: NetworkGrant? = null): RevisionCheck = withContext(Dispatchers.IO) {
        val installed = installed(source).definition
        val address = installed.origin.takeIf { it.kind == ImportOrigin.Kind.Url }?.location
            ?: throw RevisionException(RevisionError.NoUpdateAddress)
        val root = scratch()
        try {
            SourceBroker(root.toPath()).use { broker ->
                val grant = downloadGrant ?: NetworkGrant(origin(address))
                require(origin(grant.origin) == origin(address) && grant.headers.isEmpty())
                val session = broker.open(SourceScope("update-download", UUID.randomUUID().toString(), installed.profile),
                    listOf(grant))
                val preview = sources.importer.previewUrl(address, session, installed.profile)
                RevisionCheck(installed, preview, preview.issues.isEmpty() && preview.candidates.any {
                    it.importKey == installed.importKey && it.rawJson == installed.rawJson
                })
            }
        } finally { root.deleteRecursively() }
    }

    /** Candidate has been explicitly selected in import preview. A changed key requires a separate explicit approval. */
    suspend fun apply(source: Identifier, candidate: DefinitionReference, approvedOrigins: List<NetworkGrant>,
        allowIdentityChange: Boolean = false) = withContext(Dispatchers.IO) {
        val before = installed(source)
        val definition = sources.definitions.list().singleOrNull { it.reference() == candidate }
            ?: throw RevisionException(RevisionError.Stale)
        if (definition.sourceId != before.definition.sourceId ||
            !allowIdentityChange && definition.importKey != before.definition.importKey) throw RevisionException(RevisionError.IdentityChanged)
        val next = if (definition.origin.kind == ImportOrigin.Kind.Paste && before.definition.origin.kind == ImportOrigin.Kind.Url)
            definition.copy(origin = before.definition.origin) else definition
        replace(before, next, approvedOrigins)
    }

    /** Permissions apply to the installed snapshot, including after rollback or a rejected import. */
    suspend fun updatePermissions(source: Identifier, approvedOrigins: List<NetworkGrant>) = withContext(Dispatchers.IO) {
        val before = installed(source)
        replace(before, before.definition, approvedOrigins)
    }

    suspend fun rollback(source: Identifier, approvedOrigins: List<NetworkGrant>) = withContext(Dispatchers.IO) {
        val before = installed(source)
        val previous = before.previous ?: throw RevisionException(RevisionError.NoPreviousRevision)
        replace(before, previous, approvedOrigins)
    }

    private suspend fun replace(before: InstalledRuleSource, next: SourceDefinition, grants: List<NetworkGrant>) {
        if (next.profile != before.definition.profile) throw RevisionException(RevisionError.ProfileChanged)
        if (!next.enabled || grants.isEmpty() || grants.size > 32) throw RevisionException(RevisionError.InvalidCandidate)
        if (next.contentDigest == before.definition.contentDigest && grants == before.origins) return
        val id = ImportedRuleSources.id(before.definition)
        val generation = accounts.current(id).generation
        validate(next, grants)
        currentCoroutineContext().ensureActive()
        if (accounts.current(id).generation != generation) throw RevisionException(RevisionError.Stale)
        sources.replaceRevision(before.definition, next, grants, generation)
    }

    private suspend fun validate(definition: SourceDefinition, grants: List<NetworkGrant>) {
        val root = scratch()
        val namespace = "revision-validation-${UUID.randomUUID()}"
        val ticket = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, namespace)
        try {
            withTimeout(30000) {
                SourceBroker(root.toPath()).use { broker ->
                    val session = broker.open(SourceScope(namespace, definition.sourceId, definition.profile), grants)
                    val raw = Json.parseToJsonElement(definition.rawJson).jsonObject
                    val base = raw.getValue("bookSourceUrl").jsonPrimitive.content
                    if (session.permissionFailure(base) != null) throw RevisionException(RevisionError.PermissionRequired)
                    // Adapter construction checks structure; initialization runs only in the real isolated runner.
                    RuleSource(definition, ticket, authority, session, runner).use {
                        for ((field, required) in mapOf("ruleBookInfo" to "name", "ruleToc" to "chapterList", "ruleContent" to "content")) {
                            if (raw[field]?.jsonObject?.get(required)?.jsonPrimitive?.content.isNullOrBlank())
                                throw RevisionException(RevisionError.InvalidCandidate)
                        }
                        val limits = ExecutionLimits(timeoutMillis = 10000)
                        val task = ExecutionTask.Rule("@js:true", RuleValue.Empty, OutputKind.Text,
                            baseUrl = base, libraryCode = raw["jsLib"]?.jsonPrimitive?.content)
                        val result = SourceExecutionBroker(ticket, authority, session, limits, base).use { port ->
                            runner.execute(ticket, task, limits, port)
                        }
                        if (result !is ExecutionResult.Success) throw RevisionException(RevisionError.InitializationFailed)
                    }
                }
            }
        } catch (cancelled: TimeoutCancellationException) { throw RevisionException(RevisionError.InitializationFailed) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RevisionException) { throw failure }
        catch (_: Exception) { throw RevisionException(RevisionError.InvalidCandidate) }
        finally { authority.revoke(ticket); root.deleteRecursively() }
    }

    private suspend fun installed(source: Identifier) = sources.installedSources().singleOrNull {
        ImportedRuleSources.id(it.definition) == source
    } ?: throw RevisionException(RevisionError.NotInstalled)

    private fun scratch(): File {
        temporary.mkdirs()
        return File(temporary, UUID.randomUUID().toString()).apply { check(mkdir()) }
    }
    private fun origin(address: String): String {
        val uri = java.net.URI(address)
        require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null)
        return java.net.URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/", null, null).toString()
    }
}
