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
    private val accounts: SourceSessionManager, private val runner: RuleTaskRunner,
    private val storageCipher: hnovel.network.StorageCipher = hnovel.network.StorageCipher.Plain,
    private val imageCache: indi.dmzz_yyhyy.lightnovelreader.data.image.SourceImageAccountCache? = null) {
    private val directory = File(context.filesDir, "rule-sources")
    val definitions by lazy { SourceDefinitionStore(File(directory, "definitions").toPath()) }
    val importer by lazy { SourceDefinitionImporter(definitions) }
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
                        runCatching { current.session?.clearAccount() }.onFailure {
                            android.util.Log.w("ImportedRuleSources", "Retired account cleanup failed")
                        }
                        current.broker?.close()
                        runCatching { imageCache?.purge(id, current.registration.metadata.accountGeneration) }.onFailure {
                            android.util.Log.w("ImportedRuleSources", "Retired image cleanup failed")
                        }
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
        old.broker?.close()
        active.remove(source)
    } }

    suspend fun installedSources(): List<InstalledRuleSource> = withContext(Dispatchers.IO) {
        restore()
        lock.withLock { active.values.map { InstalledRuleSource(it.installed.definition,
            it.installed.origins, it.installed.previous?.definition) } }
    }

    /** Validated candidate is compared again at commit; remove/account changes cannot resurrect it. */
    internal suspend fun replaceRevision(expected: SourceDefinition, next: SourceDefinition, origins: List<NetworkGrant>,
        generation: Long) = withContext(Dispatchers.IO) { lock.withLock {
        check(!restorationFailed)
        val id = id(expected)
        val old = checkNotNull(active[id]) { "Source is not installed" }
        check(old.installed.definition == expected) { "Installed revision changed" }
        require(next.sourceId == expected.sourceId && next.profile == expected.profile && next.enabled)
        require(origins.isNotEmpty() && origins.size <= 32)
        val installed = InstalledSource(next, origins.map { it.copy(headers = it.headers.toMap()) },
            SavedRevision(expected, old.installed.origins))
        accounts.withCurrent(id) { account ->
            check(account.generation == generation) { "Account changed during validation" }
            val broker = SourceBroker(File(directory, "runtime").toPath(), cipher = storageCipher)
            val session = try { broker.open(SourceScope(id.namespace, id.id, next.profile, generation), installed.origins) }
                catch (failure: Exception) { broker.close(); throw failure }
            val ticket = authority.issue(id.id, next.profile, next.contentDigest, id.namespace, generation)
            try {
                val source = RuleSource(next, ticket, authority, session, runner)
                val metadata = old.registration.metadata.copy(item = old.registration.metadata.item.copy(name = next.displayName),
                    revision = next.contentDigest, capabilities = old.registration.metadata.capabilities.let {
                        if (source.canSearch) it + SourceCapability.Search else it - SourceCapability.Search
                    }.let {
                        if (source.canLogin) it + SourceCapability.Login else it - SourceCapability.Login
                    })
                val registration = registry.replace(old.registration, RuleWebBookDataSource(id, source), metadata, ticket) {
                    // This runs under the authority fence: old Cookie commits cannot land after the snapshot.
                    old.session?.let(session::inheritCookies)
                    save(active.values.map { if (it === old) installed else it.installed })
                }
                active[id] = Binding(installed, registration, broker, session, source)
                old.broker?.close()
            } catch (failure: Exception) { authority.revoke(ticket); broker.close(); throw failure }
        }
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
        val broker = SourceBroker(File(directory, "runtime").toPath(), cipher = storageCipher)
        val session = try { broker.open(SourceScope(id.namespace, id.id, definition.profile, generation), installed.origins) }
            catch (failure: Exception) { broker.close(); throw failure }
        val ticket = authority.issue(id.id, definition.profile, definition.contentDigest, id.namespace, generation)
        val source = try { RuleSource(definition, ticket, authority, session, runner) }
            catch (failure: Exception) { authority.revoke(ticket); broker.close(); throw failure }
        val metadata = SourceMetadata(WebDataSourceItem(id, definition.displayName, "Imported source"), buildSet {
            addAll(listOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent, SourceCapability.Images))
            if (source.canSearch) add(SourceCapability.Search)
            if (source.canLogin) add(SourceCapability.Login)
        }, revision = definition.contentDigest, accountGeneration = generation)
        val registration = try { beforePublish(); registry.register(RuleWebBookDataSource(id, source), metadata) }
            catch (failure: Exception) { source.close(); broker.close(); throw failure }
        Binding(installed, registration, broker, session, source)
    }

    internal suspend fun loginTarget(id: Identifier): RuleLoginTarget = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val current = checkNotNull(active[id]) { "Source is not installed" }
            RuleLoginTarget(id, current.registration.metadata.revision, current.registration.metadata.accountGeneration,
                checkNotNull(current.rule), checkNotNull(current.session))
        }
    }

    internal suspend fun rotateAccount(id: Identifier, expectedGeneration: Long? = null): RuleLoginTarget = withContext(Dispatchers.IO) {
        restore()
        lock.withLock {
            val current = checkNotNull(active[id]) { "Source is not installed" }
            if (expectedGeneration != null) check(accounts.current(id).generation == expectedGeneration) { "Login attempt is stale" }
            accounts.begin(id)
            current.registration.unregister()
            try {
                current.session?.clearAccount()
                imageCache?.purge(id, current.registration.metadata.accountGeneration)
            } finally {
                current.broker?.close()
                active[id] = restoreBinding(current.installed)
            }
            val next = active.getValue(id)
            RuleLoginTarget(id, next.registration.metadata.revision, next.registration.metadata.accountGeneration,
                checkNotNull(next.rule), checkNotNull(next.session))
        }
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
        active.values.forEach { it.registration.unregister(); it.broker?.close() }
        active.clear()
    }

    @Serializable private data class SavedRevision(val definition: SourceDefinition, val origins: List<NetworkGrant>)
    @Serializable private data class InstalledSource(val definition: SourceDefinition, val origins: List<NetworkGrant>, val previous: SavedRevision? = null)
    private data class Binding(val installed: InstalledSource, val registration: SourceRegistration, val broker: SourceBroker?,
        val session: hnovel.network.SourceSession? = null, val rule: RuleSource? = null)
    companion object {
        private const val MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024
        fun id(definition: SourceDefinition) = Identifier("rules", definition.sourceId)
    }
}

data class InstalledRuleSource(val definition: SourceDefinition, val origins: List<NetworkGrant>, val previous: SourceDefinition?)

internal data class RuleLoginTarget(val source: Identifier, val revision: String, val generation: Long,
    val rules: RuleSource, val session: hnovel.network.SourceSession)
