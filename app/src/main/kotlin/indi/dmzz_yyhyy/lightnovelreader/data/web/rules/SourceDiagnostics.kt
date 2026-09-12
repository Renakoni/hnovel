package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.*
import hnovel.execution.ExecutionAuthority
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable enum class DiagnosticStage { Search, Information, Directory, Content, Discovery, LoginForm }
@Serializable data class SourceDiagnosticReport(val sourceId: String, val profile: String, val revision: String,
    val accountGeneration: Long, val stage: DiagnosticStage, val result: String,
    val field: String?, val count: Int, val events: List<ContentTraceEvent>, val truncated: Boolean,
    val engine: String = "rhino-1.8.1 / legado-da17bb2", val authentication: String = "isolated-anonymous") {
    fun export(): String = Json { prettyPrint = true }.encodeToString(this)
}

/** Diagnostics execute production RuleSource with isolated disposable state and the installed grants. */
@Singleton
class SourceDiagnostics @Inject constructor(@ApplicationContext private val context: Context,
    private val sources: ImportedRuleSources, private val runner: RuleTaskRunner,
    private val authority: ExecutionAuthority, private val accounts: SourceSessionManager,
    private val registry: WebSourceRegistry, private val cipher: StorageCipher,
    private val browser: BrowserExecutor? = null) {
    suspend fun run(source: Identifier, stage: DiagnosticStage, keyword: String, bookUrl: String, chapterUrl: String, exploreUrl: String = ""): SourceDiagnosticReport =
        withContext(Dispatchers.IO) {
            val definition = sources.installedSources().single { ImportedRuleSources.id(it.definition) == source }
            val account = accounts.current(source).generation
            val namespace = "diagnostic-${UUID.randomUUID()}"
            val directory = File(context.cacheDir, namespace)
            val ticket = authority.issue(source.id, definition.definition.profile, definition.definition.contentDigest, namespace, account)
            val events = mutableListOf<ContentTraceEvent>()
            var truncated = false
            val trace = ContentTrace { event -> synchronized(events) {
                if (events.size < 256) events += event else truncated = true
            } }
            var result = "Success"
            var field: String? = null
            var count = 0
            try {
                SourceBroker(directory.toPath(), cipher = cipher, browser = browser).use { broker ->
                    val session = broker.open(SourceScope(namespace, source.id, definition.definition.profile, account), definition.origins)
                    val monitor = launch {
                        registry.sources.collect { listings ->
                            if (listings.none { it.metadata.id == source && it.metadata.revision == ticket.revision && it.metadata.accountGeneration == account }) {
                                authority.revoke(ticket); broker.close()
                            }
                        }
                    }
                    try { RuleSource(definition.definition, ticket, authority, session, runner, trace).use { rules ->
                        count = when (stage) {
                            DiagnosticStage.LoginForm -> rules.loginForm().fields.size
                            DiagnosticStage.Search -> rules.search(keyword).size
                            DiagnosticStage.Information -> { rules.information(bookUrl); 1 }
                            DiagnosticStage.Directory -> rules.directory(bookUrl).size
                            DiagnosticStage.Content -> rules.content(bookUrl, chapterUrl).parts.size
                            DiagnosticStage.Discovery -> if (exploreUrl.isBlank()) rules.openDiscovery("diagnostic").catalog().rows.size
                                else rules.discovery(exploreUrl).size
                        }
                        currentCoroutineContext().ensureActive()
                        check(authority.accepts(ticket))
                    } } finally { monitor.cancelAndJoin() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SourceContentException) { result = failure.code.name; field = failure.field }
            catch (_: Exception) { result = "HostFailure" }
            finally { authority.revoke(ticket); directory.deleteRecursively() }
            SourceDiagnosticReport(source.id, ticket.profile, ticket.revision, account, stage, result, field, count, events.toList(), truncated)
        }
}
