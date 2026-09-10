package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.RuleSource
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Initial host activation and restoration. Import preview/commit never grants runtime authority. */
@Singleton
class ImportedRuleSources @Inject constructor(@ApplicationContext context: Context,
    private val registry: WebSourceRegistry, private val authority: ExecutionAuthority,
    private val accounts: SourceSessionManager, private val runner: RuleTaskRunner) {
    private val directory = File(context.filesDir, "rule-sources")
    val definitions by lazy { SourceDefinitionStore(File(directory, "definitions").toPath()) }
    val importer by lazy { SourceDefinitionImporter(definitions) }
    private val broker by lazy { SourceBroker(File(directory, "runtime").toPath()) }
    private val committed = AtomicFile(File(directory, "active.json"))
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = linkedMapOf<Identifier, Binding>()
    private var restored = false
    var restorationFailed = false
        private set

    init {
        scope.launch {
            accounts.changes.collect { generations -> lock.withLock {
                for ((id, generation) in generations) {
                    val current = active[id] ?: continue
                    if (current.registration.metadata.accountGeneration != generation) {
                        current.registration.unregister()
                        current.session?.close()
                        active[id] = restoreBinding(current.installed)
                    }
                }
            } }
        }
    }

    suspend fun restore() = withContext(Dispatchers.IO) { lock.withLock {
        if (restored) return@withLock
        val installed = try {
            committed.openRead().use {
                check(it.channel.size() <= MAX_SNAPSHOT_BYTES) { "Installed source snapshot exceeds quota" }
                Json.decodeFromString(ListSerializer(InstalledSource.serializer()), it.readBytes().toString(Charsets.UTF_8))
            }.also { entries ->
                check(entries.size <= 256 && entries.map { it.definition.sourceId }.distinct().size == entries.size)
            }
        } catch (_: java.io.FileNotFoundException) { emptyList() }
        catch (_: Exception) {
            restorationFailed = true
            emptyList()
        }
        installed.forEach { entry -> active[id(entry.definition)] = restoreBinding(entry) }
        restored = true
    } }

    /** The caller explicitly approves origins after preview; pending revisions are not activated implicitly. */
    suspend fun activate(reference: DefinitionReference, approvedOrigins: List<NetworkGrant>): Identifier = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            check(!restorationFailed) { "Installed source snapshot could not be restored" }
            val definition = definitions.list().singleOrNull { it.reference() == reference }
                ?: error("Definition preview is no longer current")
            require(definition.enabled && approvedOrigins.isNotEmpty() && approvedOrigins.size <= 32)
            val identity = id(definition)
            active[identity]?.let {
                check(it.installed.definition == definition && it.installed.origins == approvedOrigins) { "Revision/grant replacement belongs to the update service" }
                return@withLock identity
            }
            val installed = InstalledSource(definition, approvedOrigins.map { it.copy(headers = it.headers.toMap()) })
            // Constructing the adapter opens no network and runs no source code.
            val previous = active.values.map { it.installed }
            var saved = false
            val binding = try { bind(installed) { save(previous + installed); saved = true } }
                catch (failure: Exception) {
                    if (saved) save(previous)
                    throw failure
                }
            active[identity] = binding
            identity
        }
    }

    /** Saved books, progress and readable caches belong to the host and survive removal. */
    suspend fun remove(source: Identifier) = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
        val old = active[source] ?: return@withLock
        save(active.filterKeys { it != source }.values.map { it.installed })
        old.registration.unregister()
        old.session?.close()
        active.remove(source)
    } }

    private fun restoreBinding(installed: InstalledSource): Binding = try { bind(installed) }
    catch (_: Exception) {
        val metadata = SourceMetadata(WebDataSourceItem(id(installed.definition), installed.definition.displayName, "Imported source"),
            emptySet(), revision = installed.definition.contentDigest, accountGeneration = accounts.current(id(installed.definition)).generation)
        Binding(installed, registry.register(metadata) { throw SourceUnavailableException(metadata.id) }, null)
    }

    private fun bind(installed: InstalledSource, beforePublish: () -> Unit = {}): Binding = accounts.withCurrent(id(installed.definition)) { account ->
        val definition = installed.definition
        require(definition.enabled && definition.profile in setOf(LEGADO_PROFILE, EXTENSION_PROFILE))
        val id = id(definition)
        val generation = account.generation
        val session = broker.open(SourceScope(id.namespace, id.id, definition.profile, generation), installed.origins)
        val ticket = authority.issue(id.id, definition.profile, definition.contentDigest, id.namespace, generation)
        val source = try { RuleSource(definition, ticket, authority, session, runner) }
            catch (failure: Exception) { authority.revoke(ticket); session.close(); throw failure }
        val metadata = SourceMetadata(WebDataSourceItem(id, definition.displayName, "Imported source"), buildSet {
            addAll(listOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent, SourceCapability.Images))
            if (source.canSearch) add(SourceCapability.Search)
        }, revision = definition.contentDigest, accountGeneration = generation)
        val registration = try { beforePublish(); registry.register(RuleWebBookDataSource(id, source), metadata) }
            catch (failure: Exception) { source.close(); session.close(); throw failure }
        Binding(installed, registration, session)
    }

    private fun save(installed: List<InstalledSource>) {
        check(installed.size <= 256)
        directory.mkdirs()
        val bytes = Json.encodeToString(ListSerializer(InstalledSource.serializer()), installed).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_SNAPSHOT_BYTES)
        val output = committed.startWrite()
        try { output.write(bytes); committed.finishWrite(output) }
        catch (failure: Exception) { committed.failWrite(output); throw failure }
    }

    /** Stop process-owned registrations without changing the durable activation snapshot. */
    internal suspend fun stop() = lock.withLock {
        scope.cancel()
        active.values.forEach { it.registration.unregister() }
        active.clear()
        broker.close()
    }

    @Serializable private data class InstalledSource(val definition: SourceDefinition, val origins: List<NetworkGrant>)
    private data class Binding(val installed: InstalledSource, val registration: SourceRegistration, val session: hnovel.network.SourceSession?)
    companion object {
        private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
        fun id(definition: SourceDefinition) = Identifier("rules", definition.sourceId)
    }
}
