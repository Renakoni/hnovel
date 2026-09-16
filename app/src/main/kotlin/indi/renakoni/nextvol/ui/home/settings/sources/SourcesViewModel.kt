package indi.renakoni.nextvol.ui.home.settings.sources

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.content.LoginForm
import hnovel.content.SourceContentException
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.data.web.zlibrary.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.utils.ofId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.UUID
import javax.inject.Inject

data class SourceManagementState(val installed: List<InstalledRuleSource> = emptyList(),
    val registry: List<SourceListing> = emptyList(), val selected: Identifier? = null,
    val preview: ImportPreview? = null, val updateTarget: Identifier? = null,
    val previewOrigins: Map<Int, String> = emptyMap(),
    val busy: Boolean = false, val message: Int? = null, val loginForm: LoginForm? = null,
    val loginStatus: LoginStatus = LoginStatus.LoggedOut, val variable: String = "",
    val zLibrary: ZLibraryState = ZLibraryState(), val checks: Map<String, SourceCheckSummary> = emptyMap(),
    val network: SourceNetworkState? = null, val storedSettingsAvailable: Boolean = false,
    val accountName: String? = null, val verifications: List<VerificationPrompt> = emptyList()) {
    val ruleSettings = installed.find { ImportedRuleSources.id(it.definition) == selected }
        ?.let { RuleSettingsPresentation.read(it.definition) }
    val verification get() = verifications.firstOrNull { prompt ->
        !prompt.foreground && prompt.owner.source == selected && registry.any {
            it.metadata.id == prompt.owner.source && it.metadata.revision == prompt.owner.revision &&
                it.metadata.accountGeneration == prompt.owner.generation && it.status == SourceStatus.Ready
        }
    }
}

data class SourceNetworkState(val bypassVpn: Boolean = false, val limitation: Int? = null)

/** Screen state survives rotation; previews grant nothing and each explicit mutation has a single owner. */
@HiltViewModel
class SourcesViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val sources: ImportedRuleSources, private val updates: SourceRevisionUpdates,
    private val login: SourceLoginService, private val registry: WebSourceRegistry,
    private val zLibrary: ZLibrarySources, private val checkHistory: SourceCheckHistory = SourceCheckHistory(context),
    private val networkSettings: SourceNetworkSettings = SourceNetworkSettings(context, AndroidSourceNetworks(context)),
    private val verification: SourceVerificationCoordinator = SourceVerificationCoordinator(registry)) : ViewModel() {
    private val mutable = MutableStateFlow(SourceManagementState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var attempt: LoginAttempt? = null
    private var operationGeneration = 0
    private var openedFromDiscovery: Identifier? = null
    private var openedImportLink: String? = null

    init {
        viewModelScope.launch { registry.sources.collect { list -> mutable.update { it.copy(registry = list) } } }
        viewModelScope.launch { verification.prompts.collect { prompts ->
            val previous = state.value.verifications
            mutable.update { it.copy(verifications = prompts) }
            val selected = state.value.selected
            // Verification can finish through the global notice while this settings page remains open.
            if (selected != null && previous.any { old -> old.owner.source == selected && prompts.none { it.id == old.id } })
                refreshStoredSettings(selected)
        } }
        viewModelScope.launch { zLibrary.state.collect { state -> mutable.update { it.copy(zLibrary = state) } } }
        viewModelScope.launch {
            checkHistory.restore()
            checkHistory.results.collect { results -> mutable.update { it.copy(checks = results) } }
        }
        refresh()
    }

    private fun launch(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val generation = ++operationGeneration
        mutable.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(message = when (failure) {
                is RevisionException -> when (failure.code) {
                    RevisionError.NoUpdateAddress -> R.string.sources_no_update_url
                    RevisionError.PermissionRequired -> R.string.sources_permission_denied
                    else -> R.string.sources_revision_failed
                }
                is SourceContentException -> sourceFailureMessage(failure)
                else -> R.string.sources_action_failed
            }) } }
            finally { if (generation == operationGeneration) mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun reload() {
        val installed = sources.installedSources()
        mutable.update { it.copy(installed = installed) }
    }
    fun refresh() = launch { reload() }
    fun select(id: Identifier?) = launch { selectSource(id) }
    private suspend fun selectSource(id: Identifier?) {
        reload() // Includes the current session's redacted refusals, including background image loads.
        if (id == ZLibrarySources.ID) zLibrary.refresh()
        mutable.update { it.copy(selected = id, preview = null, updateTarget = null,
            loginStatus = if (it.selected == id) it.loginStatus else LoginStatus.LoggedOut,
            variable = if (it.selected == id) it.variable else "", storedSettingsAvailable = false,
            accountName = if (it.selected == id) it.accountName else null,
            network = id?.let(::networkState)) }
        if (id != null && mutable.value.installed.any { ImportedRuleSources.id(it.definition) == id }) {
            refreshStoredSettings(id)
        }
    }

    private suspend fun refreshStoredSettings(id: Identifier, form: LoginForm? = null) {
        if (state.value.selected != id) return
        try {
            val field = if (form != null) SourceLoginService.accountNameField(form) else state.value.ruleSettings?.accountNameField
            val saved = sources.storedSettings(id, field)
            mutable.update { if (it.selected == id) it.copy(loginStatus = SourceLoginService.savedStatus(saved.loginStatus),
                variable = saved.variable, storedSettingsAvailable = true, accountName = saved.accountName) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.update { if (it.selected == id) it.copy(storedSettingsAvailable = false, accountName = null,
                message = R.string.sources_action_failed) else it }
        }
    }

    fun openFromDiscovery(id: Identifier, signIn: Boolean) {
        if (openedFromDiscovery == id) return
        launch {
            selectSource(id)
            openedFromDiscovery = id
            if (signIn && SourceCapability.Login in registry.sources.value.find { it.metadata.id == id }.actionCapabilities()) {
                openLogin(id)
            }
        }
    }

    fun previewText(text: String, profile: String = AUTO_PROFILE) = launch { showPreview(sources.importer.preview(text, profile)) }
    fun addFanqie() = launch {
        val text = checkNotNull(SourceDefinitionImporter::class.java.getResourceAsStream("/known-sources/fanqie-taijiwang.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val preview = sources.importer.preview(text, AUTO_PROFILE)
        val index = preview.candidates.single().index
        commitSelection(preview, setOf(index), candidateOrigins(preview), false)
        state.value.installed.find { it.definition.importKey == "https://fq.taijiwang.top" }?.let {
            val id = ImportedRuleSources.id(it.definition)
            sources.setPreferences(id, enabled = true)
            selectSource(id)
        }
    }
    fun openImportLink(url: String) {
        if (openedImportLink == url) return
        openedImportLink = url
        previewUrl(url)
    }
    fun previewFile(uri: Uri, profile: String = AUTO_PROFILE) = launch {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "source.json"
        } ?: "source.json"
        val preview = context.contentResolver.openInputStream(uri)?.use { sources.importer.previewStream(it, name, profile) }
            ?: error("Source file unavailable")
        showPreview(preview)
    }
    fun previewUrl(url: String, profile: String = AUTO_PROFILE) = launch {
        val address = sourceImportUrl(url) ?: error("Invalid book-source link")
        val root = File(context.cacheDir, "source-import-${UUID.randomUUID()}")
        try { SourceBroker(root.toPath(), limits = BrokerLimits(maxResponseBytes = ImportLimits().maxBytes)).use { broker ->
            val session = broker.open(SourceScope("import", UUID.randomUUID().toString(), profile), listOf(NetworkGrant(origin(address))))
            showPreview(sources.importer.previewUrl(address, session, profile))
        } } finally { root.deleteRecursively() }
    }
    private fun showPreview(preview: ImportPreview, target: Identifier? = null) {
        mutable.update { it.copy(preview = preview, updateTarget = target,
            previewOrigins = candidateOrigins(preview), message = if (preview.issues.any { issue -> issue.code != ImportCode.UnsupportedType }) R.string.sources_import_invalid else null) }
    }
    private fun candidateOrigins(preview: ImportPreview) = preview.candidates.associate {
        it.index to SourceOriginCandidates.discover(Json.parseToJsonElement(it.rawJson).jsonObject)
            .map { candidate -> candidate.origin }.distinct().joinToString("\n")
    }
    fun dismissPreview() { if (!state.value.busy) mutable.update { it.copy(preview = null, previewOrigins = emptyMap(), updateTarget = null) } }

    fun commit(selected: Set<Int>, permissions: Map<Int, String>, allowIdentityChange: Boolean) = launch {
        commitSelection(checkNotNull(state.value.preview), selected, permissions, allowIdentityChange, state.value.updateTarget)
    }
    private suspend fun commitSelection(preview: ImportPreview, selected: Set<Int>, permissions: Map<Int, String>,
        allowIdentityChange: Boolean, updateTarget: Identifier? = null) {
        val snapshot = mutable.value
        require(selected.isNotEmpty())
        val candidates = preview.candidates.filter { it.index in selected }
        require(candidates.size == selected.size)
        val target = updateTarget?.let { id -> snapshot.installed.single { ImportedRuleSources.id(it.definition) == id } }
        if (target != null) require(candidates.size == 1)
        val grants = candidates.associate { it.index to grants(permissions.getValue(it.index)) }
        val selections = candidates.map { candidate ->
            val decision = if (target != null && target.definition.importKey != candidate.importKey) {
                require(allowIdentityChange); ImportDecision.MapIdentity(target.definition.reference())
            } else candidate.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add
            ImportSelection(candidate.index, decision)
        }
        val committed = sources.importer.commit(preview, selections)
        var failed = committed.error != null
        try {
            val definitions = sources.definitions.list().associateBy { it.reference() }
            val installed = snapshot.installed.associateBy { ImportedRuleSources.id(it.definition) }
            val additions = linkedMapOf<DefinitionReference, List<NetworkGrant>>()
            for (item in committed.items) {
                val reference = item.reference
                if (reference == null || item.error != null) { failed = true; continue }
                try {
                    val definition = definitions.getValue(reference)
                    val id = ImportedRuleSources.id(definition)
                    val previous = installed[id]
                    if (previous != null) {
                        if (previous.definition != definition || previous.origins != grants.getValue(item.index))
                            updates.apply(id, reference, grants.getValue(item.index), allowIdentityChange)
                    } else additions[reference] = grants.getValue(item.index)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
            if (additions.isNotEmpty() && sources.activateBatch(additions, enableNew = true).size != additions.size) failed = true
        } finally {
            // Import definitions are already committed. Retrying requires a fresh preview of that state.
            withContext(NonCancellable) {
                reload()
                mutable.update { it.copy(preview = null, previewOrigins = emptyMap(), updateTarget = null) }
            }
        }
        mutable.update { it.copy(message = if (failed) R.string.sources_import_partial else R.string.sources_saved) }
    }
    fun checkUpdate(id: Identifier) = launch {
        val checked = updates.check(id)
        if (checked.unchanged) mutable.update { it.copy(message = R.string.sources_up_to_date) }
        else showPreview(checked.preview, id)
    }
    fun rollback(id: Identifier, permissions: String) = launch {
        updates.rollback(id, grants(permissions)); selectSource(id)
        mutable.update { it.copy(message = R.string.sources_saved) }
    }
    fun setEnabled(id: Identifier, enabled: Boolean) = launch {
        val hasDiscovery = state.value.installed.firstOrNull { ImportedRuleSources.id(it.definition) == id }?.hasDiscovery == true
        // Enabling a source is the single user action that makes its catalogue visible.
        // Sources without exploreUrl remain search-only.
        sources.setPreferences(id, enabled = enabled, discoveryVisible = enabled && hasDiscovery)
        if (state.value.selected == id) selectSource(id) else reload()
    }
    fun setDiscoveryVisible(id: Identifier, visible: Boolean) = launch {
        sources.setPreferences(id, discoveryVisible = visible); selectSource(id)
    }
    fun setZLibraryEnabled(enabled: Boolean) = launch {
        zLibrary.update(zLibrary.state.value.settings.copy(enabled = enabled))
    }
    private fun networkState(id: Identifier): SourceNetworkState {
        val installed = state.value.installed.find { ImportedRuleSources.id(it.definition) == id }
        val listing = state.value.registry.find { it.metadata.id == id }
        val limitation = when {
            installed != null -> if (RuleSettingsPresentation.read(installed.definition).nativeBrowser &&
                !AndroidSourceBrowser.supportsVpnBypass(context)) R.string.sources_network_native else null
            id == ZLibrarySources.ID -> null
            id == "Wenku8".ofId() && listing?.metadata?.builtIn == true -> null
            listing?.metadata?.builtIn == false -> R.string.sources_network_plugin
            else -> R.string.sources_network_unsupported
        }
        return SourceNetworkState(networkSettings.mode(id) == SourceNetworkMode.BypassVpn, limitation)
    }
    fun setBypassVpn(enabled: Boolean) = launch {
        val id = checkNotNull(state.value.selected)
        check(!enabled || networkState(id).limitation == null)
        networkSettings.setBypassVpn(id, enabled)
        // No source registration/revision or account change; running chains keep their route.
        mutable.update { it.copy(network = networkState(id), message = R.string.sources_saved) }
    }
    fun saveZLibrary(origin: String, permissions: String) = launch {
        zLibrary.update(zLibrary.state.value.settings.copy(origin = origin,
            origins = permissions.lines().filter(String::isNotBlank)))
        mutable.update { it.copy(message = R.string.sources_saved) }
    }
    fun saveConfiguration(id: Identifier, variable: String?, permissions: String) = launch {
        require(variable == null || variable.length <= 32768)
        val approved = grants(permissions)
        // A closed variable editor passes null. Preserve its value when only grants are saved.
        if (variable != null && approved.isEmpty())
            sources.saveVariable(id, variable)
        updates.updatePermissions(id, approved)
        if (variable != null && approved.isNotEmpty())
            sources.saveVariable(id, variable)
        selectSource(id)
        mutable.update { it.copy(message = R.string.sources_saved) }
    }
    fun remove(id: Identifier) = launch {
        sources.remove(id); reload()
        mutable.update { it.copy(selected = null, message = R.string.sources_removed) }
    }
    fun beginLogin(id: Identifier) = launch { openLogin(id) }
    private suspend fun openLogin(id: Identifier) {
        val definition = sources.installedSources().single { ImportedRuleSources.id(it.definition) == id }.definition
        val declaration = RuleSettingsPresentation.read(definition)
        if (!declaration.loginDeclared || declaration.loginErrorField != null)
            throw SourceContentException(hnovel.content.ContentError.InvalidRule, declaration.loginErrorField ?: "loginUi")
        check(registry.resolve(id) is SourceResolution.Ready) { "Source is not initialized" }
        // Keep a handle even if cancellation arrives just after the account has rotated.
        val active = withContext(NonCancellable) { login.begin(id).also { attempt = it } }
        var form: LoginForm? = null
        try {
            val loaded = login.form(active).also { form = it }
            currentCoroutineContext().ensureActive()
            if (loaded.browserUrl != null && loaded.fields.isEmpty()) {
                login.submit(active, emptyMap())
                attempt = null
                mutable.update { it.copy(loginForm = null) }
            } else mutable.update { it.copy(loginForm = loaded) }
        } catch (failure: Exception) {
            withContext(NonCancellable) { login.cancel(active) }
            if (attempt === active) attempt = null
            throw failure
        } finally {
            withContext(NonCancellable) { refreshStoredSettings(id, form) }
        }
    }
    fun submitLogin(values: Map<String, String>, action: String? = null) = launch {
        val active = checkNotNull(attempt)
        val submittedForm = state.value.loginForm
        try {
            login.submit(active, values, action)
            val form = if (action == null) null else login.form(active)
            if (action == null) attempt = null
            mutable.update { it.copy(loginForm = form) }
        } finally {
            withContext(NonCancellable) { refreshStoredSettings(active.source, submittedForm) }
        }
    }
    fun logout(id: Identifier) = launch { login.logout(id); refreshStoredSettings(id) }
    fun verifyPending() = launch {
        val prompt = state.value.verification ?: return@launch
        // The coordinator owns the original request and account. Opening verification is not a new login attempt.
        try { verification.verifyBackground(prompt.id) }
        finally { withContext(NonCancellable) { refreshStoredSettings(prompt.owner.source) } }
    }
    fun cancelLogin() {
        operation?.cancel()
        val generation = ++operationGeneration
        mutable.update { it.copy(busy = true) }
        val active = attempt; attempt = null
        viewModelScope.launch {
            withContext(NonCancellable) {
                if (active != null) {
                    login.cancel(active)
                    if (generation == operationGeneration) refreshStoredSettings(active.source)
                }
            }
            if (generation == operationGeneration) mutable.update { it.copy(loginForm = null, busy = false) }
        }
    }
    fun cancel() { operation?.cancel() }
    override fun onCleared() {
        val active = attempt
        if (active != null) CoroutineScope(Dispatchers.IO).launch { login.cancel(active) }
    }
    companion object {
        fun origin(url: String): String {
            val uri = URI(url.trim())
            require(uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.userInfo == null)
            return URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/", null, null).toString()
        }
        fun grants(text: String): List<NetworkGrant> = text.lines().filter { it.isNotBlank() }.map { NetworkGrant(origin(it)) }
            .distinctBy { it.origin }.also { require(it.size <= 32) }
    }
}
