package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import indi.renakoni.nextvol.R
import hnovel.imports.EXTENSION_PROFILE
import hnovel.imports.LEGADO_PROFILE
import hnovel.imports.AUTO_PROFILE
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.data.web.SourceCapability
import indi.renakoni.nextvol.data.web.SourceListing
import indi.renakoni.nextvol.data.web.SourceStatus
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import indi.renakoni.nextvol.data.web.rules.LoginStatus
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.flow.first

fun NavGraphBuilder.settingsSourcesDestination() {
    composable<Route.Main.Settings.SourceImport> { entry ->
        val route = entry.toRoute<Route.Main.Settings.SourceImport>()
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(model) {
            model.state.first { !it.busy }
            model.openImportLink(route.url)
        }
        SourcesScreen(state, model,
            onDiagnostics = { id -> nav.navigate(Route.Main.Settings.SourceDiagnostic(id.namespace, id.id)) },
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
    composable<Route.Main.Settings.Sources> {
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        SourcesScreen(state, model,
            onDiagnostics = { id -> nav.navigate(Route.Main.Settings.SourceDiagnostic(id.namespace, id.id)) },
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
    composable<Route.Main.Settings.SourceDetail> { entry ->
        val route = entry.toRoute<Route.Main.Settings.SourceDetail>()
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(model) {
            model.state.first { !it.busy }
            model.openFromDiscovery(Identifier(route.namespace, route.sourceId), route.login)
        }
        SourcesScreen(state, model,
            onDiagnostics = { id -> nav.navigate(Route.Main.Settings.SourceDiagnostic(id.namespace, id.id)) },
            onSearch = { id -> nav.navigate(Route.Main.Explore.Search(id.namespace, id.id)) }) { nav.popBackStack() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(state: SourceManagementState, model: SourcesViewModel,
    onDiagnostics: (Identifier) -> Unit, onSearch: (Identifier) -> Unit = {}, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf(AUTO_PROFILE) }
    var advancedImport by rememberSaveable { mutableStateOf(false) }
    val listState = remember(state.selected, adding) { LazyListState() }
    LaunchedEffect(state.message) { if (state.message == R.string.sources_saved) adding = false }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.previewFile(uri, profile) } }
    val installed = state.installed.find { ImportedRuleSources.id(it.definition) == state.selected }
    val selectedEntry = state.registry.find { it.metadata.id == state.selected }
    fun back() {
        when {
            state.loginForm != null -> model.cancelLogin()
            state.busy -> model.cancel()
            state.preview != null -> model.dismissPreview()
            state.selected != null -> model.select(null)
            adding -> adding = false
            else -> onBack()
        }
    }
    BackHandler { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (state.selected == ZLibrarySources.ID) "Z-Library"
            else installed?.definition?.displayName ?: selectedEntry?.metadata?.item?.name ?: stringResource(R.string.sources_title)) },
            navigationIcon = { IconButton(onClick = { back() }) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            } })
    }) { padding ->
        if (state.preview != null) {
            SourceImportPreview(state, model, Modifier.padding(padding))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) } }
            state.message?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.primary) } }
            if (state.selected == ZLibrarySources.ID) {
                item(key = "zlibrary-settings") { ZLibrarySettingsEditor(state.zLibrary, state.busy, state.registry.find { it.metadata.id == ZLibrarySources.ID },
                    onEnabled = model::setZLibraryEnabled, onSave = model::saveZLibrary,
                    onSearch = { onSearch(ZLibrarySources.ID) }, network = state.network, onBypassVpn = model::setBypassVpn) }
            } else if (installed != null) {
                item(key = "rule-settings") {
                    val definition = installed.definition
                    val settings = checkNotNull(state.ruleSettings)
                    var configuration by rememberSaveable(definition.sourceId, state.variable) { mutableStateOf(state.variable) }
                    var permissions by rememberSaveable(definition.sourceId, installed.origins) { mutableStateOf(installed.origins.joinToString("\n") { it.origin }) }
                    var showDetails by rememberSaveable(definition.sourceId) { mutableStateOf(false) }
                    var editVariable by rememberSaveable(definition.sourceId) { mutableStateOf(false) }
                    val entry = state.registry.find { it.metadata.id == state.selected }
                    val capabilities = entry.actionCapabilities()
                    val active = entry?.status == SourceStatus.Ready && capabilities.isNotEmpty()
                    val check = state.checks[definition.sourceId]
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(text = stringResource(R.string.sources_basic_section))
                        if (entry?.status == SourceStatus.Failed) Text(stringResource(R.string.sources_action_failed), color = MaterialTheme.colorScheme.error)
                        if (installed.preferences.enabled && installed.origins.isEmpty()) Text(stringResource(R.string.sources_awaiting_permissions))
                        val enabledLabel = stringResource(R.string.sources_enabled)
                        ListItem(headlineContent = { Text(enabledLabel) },
                            trailingContent = { Switch(installed.preferences.enabled,
                                { model.setEnabled(state.selected!!, it) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = enabledLabel }) })
                        if (SourceCapability.Search in capabilities) {
                            Button(onClick = { onSearch(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.explore_search)) }
                        }
                        state.network?.let {
                            SectionHeader(text = stringResource(R.string.sources_network_section))
                            SourceNetworkSection(it, state.busy, model::setBypassVpn)
                        }
                        val savedAccount = state.storedSettingsAvailable && state.loginStatus != LoginStatus.LoggedOut
                        if (settings.loginDeclared || settings.loginErrorField != null || savedAccount || state.verification != null) {
                            SectionHeader(text = stringResource(if (settings.loginDeclared || settings.loginErrorField != null || savedAccount)
                                R.string.sources_account_section else R.string.sources_verification_section))
                            if (settings.loginErrorField != null) {
                                Text(stringResource(R.string.sources_login_definition_error, settings.loginErrorField), color = MaterialTheme.colorScheme.error)
                            }
                            if (settings.loginErrorField == null || savedAccount || state.verification != null) {
                                SourceAccountSection(state.loginStatus.takeIf { state.storedSettingsAvailable }, state.accountName,
                                    entry?.status in setOf(SourceStatus.Registered, SourceStatus.Ready),
                                    settings.loginErrorField == null && settings.loginDeclared && SourceCapability.Login in capabilities,
                                    state.busy, state.verification,
                                    onLogin = { model.beginLogin(state.selected!!) }, onLogout = { model.logout(state.selected!!) },
                                    onRetry = { model.select(state.selected) }, onVerify = model::verifyPending)
                            }
                        }
                        if (settings.variableDescription.isNotBlank()) {
                            SectionHeader(text = stringResource(R.string.sources_configuration))
                            Text(settings.variableDescription)
                        }
                        TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.sources_advanced)) }
                        if (showDetails) {
                            TextButton(onClick = { editVariable = !editVariable }, enabled = !state.busy && state.storedSettingsAvailable) {
                                Text(stringResource(R.string.sources_edit_variable))
                            }
                            if (editVariable) OutlinedTextField(configuration, { configuration = it }, Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.sources_variable)) }, enabled = !state.busy && state.storedSettingsAvailable)
                            SourceCheckStatus(check, check?.revision == definition.contentDigest && check.accountGeneration == entry?.metadata?.accountGeneration, details = true)
                            if (installed.deniedOrigins.isNotEmpty()) {
                                Text(stringResource(R.string.sources_denied_origins), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.sources_origin_candidates_help))
                                installed.deniedOrigins.forEach { request ->
                                    SourcePermissionCandidate(request.origin, request.kind.name, permissions, state.busy) { permissions = it }
                                }
                            }
                            OutlinedTextField(permissions, { permissions = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) }, enabled = !state.busy)
                            Button(onClick = { model.saveConfiguration(state.selected!!, configuration.takeIf { editVariable && state.storedSettingsAvailable }, permissions) }, enabled = !state.busy) {
                                Text(stringResource(if (editVariable && state.storedSettingsAvailable) R.string.sources_save else R.string.sources_save_permissions))
                            }
                            OutlinedButton(onClick = { model.checkUpdate(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_check_update)) }
                            OutlinedButton(onClick = { onDiagnostics(state.selected!!) }, enabled = !state.busy && active) { Text(stringResource(R.string.sources_diagnostics)) }
                            Text(stringResource(R.string.sources_format, definition.format), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (definition.profile == EXTENSION_PROFILE) R.string.sources_profile_extension else R.string.sources_profile_standard),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.sources_revision, definition.revision.toString()), style = MaterialTheme.typography.bodySmall)
                            if (installed.previous != null) OutlinedButton(onClick = { rollback = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_rollback)) }
                        }
                        TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_delete), color = MaterialTheme.colorScheme.error) }
                    }
                    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.sources_delete)) },
                        text = { Text(stringResource(R.string.sources_delete_help)) }, confirmButton = { TextButton(onClick = { deleting = false; model.remove(state.selected!!) }) { Text(stringResource(R.string.sources_delete)) } },
                        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } })
                    if (rollback) AlertDialog(onDismissRequest = { rollback = false }, title = { Text(stringResource(R.string.sources_rollback)) },
                        text = { Text(stringResource(R.string.sources_rollback_help)) }, confirmButton = { TextButton(onClick = { rollback = false; model.rollback(state.selected!!, permissions) }) { Text(stringResource(R.string.sources_rollback)) } },
                        dismissButton = { TextButton(onClick = { rollback = false }) { Text(stringResource(android.R.string.cancel)) } })
                }
            } else if (state.selected != null) {
                item(key = "registered-settings") {
                    RegisteredSourceSettings(selectedEntry, state.network, state.busy,
                        onSearch = { onSearch(state.selected) }, onBypassVpn = model::setBypassVpn)
                }
            } else {
                item { Button(onClick = { adding = !adding }, enabled = !state.busy) { Text(stringResource(R.string.sources_add)) } }
                if (adding) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.addFanqie() }, enabled = !state.busy) {
                            Text(stringResource(R.string.sources_fanqie))
                        }
                        TextButton(onClick = { advancedImport = !advancedImport }) { Text(stringResource(R.string.sources_advanced)) }
                        if (advancedImport) {
                            for ((value, label) in listOf(AUTO_PROFILE to R.string.sources_profile_auto, LEGADO_PROFILE to R.string.sources_profile_standard, EXTENSION_PROFILE to R.string.sources_profile_extension)) {
                                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { profile = value }) {
                                    RadioButton(selected = profile == value, onClick = { profile = value }, enabled = !state.busy)
                                    Text(stringResource(label), Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        Text(stringResource(R.string.sources_collections), style = MaterialTheme.typography.titleMedium)
                        for ((label, address) in listOf(
                            "XIU2" to "https://legado.aoaostar.com/sources/71e56d4f.json",
                            "aoaostar" to "https://legado.aoaostar.com/sources/b778fe6b.json",
                            "shidahuilang" to "https://raw.githubusercontent.com/shidahuilang/shuyuan-bak/main/good.json"
                        )) {
                            OutlinedButton(onClick = { model.previewUrl(address, profile) }, enabled = !state.busy) { Text(label) }
                        }
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Button(onClick = { model.previewUrl(url, profile) }, enabled = !state.busy && url.isNotBlank()) { Text(stringResource(R.string.sources_preview_url)) }
                        OutlinedButton(onClick = { file.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text(stringResource(R.string.sources_file)) }
                        if (advancedImport) {
                            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp), label = { Text(stringResource(R.string.sources_paste)) })
                            Button(onClick = { model.previewText(text, profile) }, enabled = !state.busy && text.isNotBlank()) { Text(stringResource(R.string.sources_preview)) }
                        }
                    }
                }
                if (!adding) {
                    item { SectionHeader(text = stringResource(R.string.sources_builtin_group)) }
                    item {
                        val entry = state.registry.find { it.metadata.id == ZLibrarySources.ID }
                        ListItem(headlineContent = { Text("Z-Library") },
                            supportingContent = { Column {
                                Text(stringResource(R.string.zlibrary_source_summary))
                            } },
                            trailingContent = {
                                if (state.zLibrary.settings.available && SourceCapability.Search in entry.actionCapabilities()) IconButton(onClick = { onSearch(ZLibrarySources.ID) }, enabled = !state.busy) {
                                    Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                                }
                            }, modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(ZLibrarySources.ID) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                    }
                    items(state.registry.filter { it.metadata.builtIn && it.metadata.id != ZLibrarySources.ID }, key = { it.metadata.id.toString() }) { entry ->
                        ListItem(headlineContent = { Text(entry.metadata.item.name) }, supportingContent = { Column {
                            Text(stringResource(R.string.sources_builtin))
                        } },
                            modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(entry.metadata.id) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            trailingContent = {
                                if (SourceCapability.Search in entry.actionCapabilities()) IconButton(onClick = { onSearch(entry.metadata.id) }, enabled = !state.busy) {
                                    Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                                }
                            })
                    }
                    item { SectionHeader(text = stringResource(R.string.sources_imported_group)) }
                    if (state.installed.isEmpty()) item { Text(stringResource(R.string.sources_empty)) }
                    items(state.installed, key = { it.definition.sourceId }) { source ->
                        val id = ImportedRuleSources.id(source.definition)
                        ListItem(headlineContent = { Text(source.definition.displayName) },
                            supportingContent = { Text(android.net.Uri.parse(source.definition.importKey).host.orEmpty()) },
                            trailingContent = { Switch(source.preferences.enabled, { model.setEnabled(id, it) }, enabled = !state.busy) },
                            modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(id) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                    }
                    val installedIds = state.installed.map { ImportedRuleSources.id(it.definition) }.toSet()
                    val plugins = state.registry.filter { !it.metadata.builtIn && it.metadata.id !in installedIds && it.metadata.id != ZLibrarySources.ID }
                    if (plugins.isNotEmpty()) item { SectionHeader(text = stringResource(R.string.sources_plugins_group)) }
                    items(plugins, key = { it.metadata.id.toString() }) { entry ->
                        ListItem(headlineContent = { Text(entry.metadata.item.name) },
                            supportingContent = { Text(entry.metadata.item.provider) },
                            modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(entry.metadata.id) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                    }
                }
            }
        }
    }
    state.loginForm?.let { form ->
        SourceLoginDialog(form, state.busy, model::submitLogin, model::cancelLogin)
    }
}

// Search and login resolve the lazy registration themselves. A failed registration cannot start work.
internal fun SourceListing?.actionCapabilities(): Set<SourceCapability> =
    if (this != null && status in setOf(SourceStatus.Registered, SourceStatus.Ready)) metadata.capabilities else emptySet()
