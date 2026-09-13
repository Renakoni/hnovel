package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

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
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.*
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
    val busy: Boolean = false, val message: Int? = null, val loginForm: LoginForm? = null,
    val loginStatus: LoginStatus = LoginStatus.LoggedOut, val variable: String = "",
    val zLibrary: ZLibraryState = ZLibraryState(), val checks: Map<String, SourceCheckSummary> = emptyMap())

/** Screen state survives rotation; previews grant nothing and each explicit mutation has a single owner. */
@HiltViewModel
class SourcesViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val sources: ImportedRuleSources, private val updates: SourceRevisionUpdates,
    private val login: SourceLoginService, private val registry: WebSourceRegistry,
    private val zLibrary: ZLibrarySources, private val checkHistory: SourceCheckHistory = SourceCheckHistory(context)) : ViewModel() {
    private val mutable = MutableStateFlow(SourceManagementState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var attempt: LoginAttempt? = null
    private var operationGeneration = 0
    private var openedFromDiscovery: Identifier? = null

    init {
        viewModelScope.launch { registry.sources.collect { list -> mutable.update { it.copy(registry = list) } } }
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
                is SourceContentException -> when (failure.code) {
                    hnovel.content.ContentError.LoginRequired -> R.string.sources_login_required
                    hnovel.content.ContentError.PermissionDenied -> R.string.sources_permission_denied
                    hnovel.content.ContentError.AddressDenied -> R.string.sources_address_denied
                    hnovel.content.ContentError.Dns -> R.string.sources_dns_failed
                    hnovel.content.ContentError.Network -> R.string.discovery_network
                    else -> R.string.sources_rule_failed
                }
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
    fun initializeSource(id: Identifier) = launch {
        if (mutable.value.selected == id) selectSource(id) else registry.resolve(id)
    }
    private suspend fun selectSource(id: Identifier?) {
        reload() // Includes the current session's redacted refusals, including background image loads.
        if (id == ZLibrarySources.ID) zLibrary.refresh()
        mutable.update { it.copy(selected = id, preview = null, updateTarget = null,
            loginStatus = LoginStatus.LoggedOut, variable = "") }
        if (id != null && registry.sources.value.any { it.metadata.id == id && it.metadata.capabilities.isNotEmpty() }) {
            if (registry.resolve(id) !is SourceResolution.Ready) return
            if (mutable.value.installed.any { ImportedRuleSources.id(it.definition) == id }) {
                val target = sources.loginTarget(id)
                val variable = target.session.read(StorageRequest(StorageArea.Config, "variable")) as StorageResult.Value
                val status = login.status(id)
                mutable.update { it.copy(loginStatus = status, variable = variable.value.orEmpty()) }
            }
        }
    }

    fun openFromDiscovery(id: Identifier, signIn: Boolean) {
        if (openedFromDiscovery == id) return
        launch {
            selectSource(id)
            openedFromDiscovery = id
            if (signIn && registry.sources.value.any { it.metadata.id == id && it.status == SourceStatus.Ready && SourceCapability.Login in it.metadata.capabilities }) {
                openLogin(id)
            }
        }
    }

    fun previewText(text: String, profile: String = LEGADO_PROFILE) = launch { showPreview(sources.importer.preview(text, profile)) }
    fun previewFile(uri: Uri, profile: String = LEGADO_PROFILE) = launch {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "source.json"
        } ?: "source.json"
        val preview = context.contentResolver.openInputStream(uri)?.use { sources.importer.previewStream(it, name, profile) }
            ?: error("Source file unavailable")
        showPreview(preview)
    }
    fun previewUrl(url: String, profile: String = LEGADO_PROFILE) = launch {
        val root = File(context.cacheDir, "source-import-${UUID.randomUUID()}")
        try { SourceBroker(root.toPath()).use { broker ->
            val session = broker.open(SourceScope("import", UUID.randomUUID().toString(), profile), listOf(NetworkGrant(origin(url))))
            showPreview(sources.importer.previewUrl(url, session, profile))
        } } finally { root.deleteRecursively() }
    }
    private fun showPreview(preview: ImportPreview, target: Identifier? = null) {
        mutable.update { it.copy(preview = preview, updateTarget = target,
            message = if (preview.issues.isNotEmpty()) R.string.sources_import_invalid else null) }
    }
    fun dismissPreview() { if (!state.value.busy) mutable.update { it.copy(preview = null, updateTarget = null) } }

    fun commit(selected: Set<Int>, permissions: Map<Int, String>, allowIdentityChange: Boolean) = launch {
        val snapshot = mutable.value
        val preview = checkNotNull(snapshot.preview)
        require(selected.isNotEmpty())
        val candidates = preview.candidates.filter { it.index in selected }
        require(candidates.size == selected.size)
        val target = snapshot.updateTarget?.let { id -> snapshot.installed.single { ImportedRuleSources.id(it.definition) == id } }
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
            for (item in committed.items) {
                val reference = item.reference
                if (reference == null || item.error != null) { failed = true; continue }
                try {
                    val definition = sources.definitions.list().single { it.reference() == reference }
                    val id = ImportedRuleSources.id(definition)
                    if (snapshot.installed.any { ImportedRuleSources.id(it.definition) == id })
                        updates.apply(id, reference, grants.getValue(item.index), allowIdentityChange)
                    else sources.activate(reference, grants.getValue(item.index))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
        } finally {
            // Import definitions are already committed. Retrying requires a fresh preview of that state.
            withContext(NonCancellable) {
                reload()
                mutable.update { it.copy(preview = null, updateTarget = null) }
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
        sources.setPreferences(id, enabled = enabled); selectSource(id)
    }
    fun setDiscoveryVisible(id: Identifier, visible: Boolean) = launch {
        sources.setPreferences(id, discoveryVisible = visible); selectSource(id)
    }
    fun setZLibraryEnabled(enabled: Boolean) = launch {
        zLibrary.update(zLibrary.state.value.settings.copy(enabled = enabled))
    }
    fun saveZLibrary(origin: String, permissions: String) = launch {
        zLibrary.update(zLibrary.state.value.settings.copy(origin = origin,
            origins = permissions.lines().filter(String::isNotBlank)))
        mutable.update { it.copy(message = R.string.sources_saved) }
    }
    fun saveConfiguration(id: Identifier, variable: String?, permissions: String) = launch {
        require(variable == null || variable.length <= 32768)
        val approved = grants(permissions)
        // An inactive editor passes null, so approving grants cannot overwrite stored configuration
        // with its empty placeholder. Save active configuration before removing the last grant.
        if (variable != null && approved.isEmpty())
            check(sources.loginTarget(id).session.write(StorageRequest(StorageArea.Config, "variable", variable)) is StorageResult.Value)
        updates.updatePermissions(id, approved)
        if (variable != null && approved.isNotEmpty())
            check(sources.loginTarget(id).session.write(StorageRequest(StorageArea.Config, "variable", variable)) is StorageResult.Value)
        selectSource(id)
        mutable.update { it.copy(message = R.string.sources_saved) }
    }
    fun remove(id: Identifier) = launch {
        sources.remove(id); reload()
        mutable.update { it.copy(selected = null, message = R.string.sources_removed) }
    }
    fun beginLogin(id: Identifier) = launch { openLogin(id) }
    private suspend fun openLogin(id: Identifier) {
        check(registry.resolve(id) is SourceResolution.Ready) { "Source is not initialized" }
        // Keep a handle even if cancellation arrives just after the account has rotated.
        val active = withContext(NonCancellable) { login.begin(id).also { attempt = it } }
        try {
            val form = login.form(active)
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(loginForm = form, loginStatus = LoginStatus.LoggedOut) }
        } catch (failure: Exception) {
            withContext(NonCancellable) { login.cancel(active) }
            if (attempt === active) attempt = null
            throw failure
        }
    }
    fun submitLogin(values: Map<String, String>, action: String? = null) = launch {
        val active = checkNotNull(attempt)
        login.submit(active, values, action)
        val status = login.status(active.source)
        if (action == null) attempt = null
        val form = if (action == null) null else login.form(active)
        mutable.update { it.copy(loginStatus = status, loginForm = form) }
    }
    fun logout(id: Identifier) = launch { login.logout(id); mutable.update { it.copy(loginStatus = LoginStatus.LoggedOut) } }
    fun cancelLogin() {
        operation?.cancel()
        val generation = ++operationGeneration
        mutable.update { it.copy(busy = true) }
        val active = attempt; attempt = null
        viewModelScope.launch {
            withContext(NonCancellable) { if (active != null) login.cancel(active) }
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
