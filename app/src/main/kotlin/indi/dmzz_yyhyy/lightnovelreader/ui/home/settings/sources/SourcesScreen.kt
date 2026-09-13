package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import indi.dmzz_yyhyy.lightnovelreader.R
import hnovel.imports.EXTENSION_PROFILE
import hnovel.imports.LEGADO_PROFILE
import indi.dmzz_yyhyy.lightnovelreader.ui.components.SectionHeader
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceCapability
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.InstalledRuleSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.LoginStatus
import indi.dmzz_yyhyy.lightnovelreader.data.web.zlibrary.ZLibrarySources
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.first

fun NavGraphBuilder.settingsSourcesDestination() {
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
    var profile by rememberSaveable { mutableStateOf(LEGADO_PROFILE) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.previewFile(uri, profile) } }
    val installed = state.installed.find { ImportedRuleSources.id(it.definition) == state.selected }
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
            else installed?.definition?.displayName ?: stringResource(R.string.sources_title)) },
            navigationIcon = { IconButton(onClick = { back() }) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            } })
    }) { padding ->
        if (state.preview != null) {
            SourceImportPreview(state, model, Modifier.padding(padding))
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) } }
            state.message?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.primary) } }
            if (state.selected == ZLibrarySources.ID) {
                item { ZLibrarySettingsEditor(state.zLibrary, state.busy,
                    onEnabled = model::setZLibraryEnabled, onSave = model::saveZLibrary,
                    onSearch = { onSearch(ZLibrarySources.ID) }) }
            } else if (installed != null) {
                item {
                    val definition = installed.definition
                    val raw = remember(definition) { Json.parseToJsonElement(definition.rawJson).jsonObject }
                    var configuration by remember(definition.contentDigest, state.variable) { mutableStateOf(state.variable) }
                    var permissions by remember(definition, installed.origins) { mutableStateOf(installed.origins.joinToString("\n") { it.origin }) }
                    var showDetails by rememberSaveable(definition.sourceId) { mutableStateOf(false) }
                    val entry = state.registry.find { it.metadata.id == state.selected }
                    val capabilities = entry?.metadata?.capabilities.orEmpty()
                    val active = capabilities.isNotEmpty()
                    val hasConfiguration = active && (raw["variableComment"]?.jsonPrimitive?.content?.isNotBlank() == true || state.variable.isNotEmpty())
                    val variableLabel = raw["variableComment"]?.jsonPrimitive?.content.orEmpty().ifBlank { stringResource(R.string.sources_configuration) }
                    val check = state.checks[definition.sourceId]
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val enabledLabel = stringResource(R.string.sources_enabled)
                        val discoveryLabel = stringResource(R.string.sources_discovery_visible)
                        ListItem(headlineContent = { Text(enabledLabel) },
                            supportingContent = { Text(stringResource(sourceStatus(installed, active))) },
                            trailingContent = { Switch(installed.preferences.enabled,
                                { model.setEnabled(state.selected!!, it) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = enabledLabel }) })
                        ListItem(headlineContent = { Text(discoveryLabel) },
                            supportingContent = { Text(stringResource(if (installed.hasDiscovery)
                                R.string.sources_discovery_help else R.string.sources_no_discovery)) },
                            trailingContent = { Switch(installed.hasDiscovery && installed.preferences.discoveryVisible,
                                { model.setDiscoveryVisible(state.selected!!, it) }, enabled = !state.busy && installed.hasDiscovery,
                                modifier = Modifier.semantics { contentDescription = discoveryLabel }) })
                        Text(stringResource(R.string.sources_disabled_help), style = MaterialTheme.typography.bodySmall)
                        SourceCheckStatus(check, check?.revision == definition.contentDigest && check.accountGeneration == entry?.metadata?.accountGeneration, details = true)
                        if (SourceCapability.Search in capabilities) {
                            Button(onClick = { onSearch(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.explore_search)) }
                        }
                        if (SourceCapability.Login in capabilities) {
                            Text(stringResource(when (state.loginStatus) {
                                LoginStatus.Authenticated -> R.string.sources_logged_in
                                LoginStatus.Required -> R.string.sources_login_required
                                LoginStatus.LoggedOut -> R.string.sources_logged_out
                            }))
                            Button(onClick = { model.beginLogin(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_login)) }
                            OutlinedButton(onClick = { model.logout(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_logout)) }
                        }
                        if (hasConfiguration) OutlinedTextField(configuration, { configuration = it }, Modifier.fillMaxWidth(), label = { Text(variableLabel) }, enabled = !state.busy)
                        HorizontalDivider()
                        Text(stringResource(R.string.sources_permissions_help))
                        Text(stringResource(R.string.sources_network_help), style = MaterialTheme.typography.bodySmall)
                        if (installed.deniedOrigins.isNotEmpty()) {
                            Text(stringResource(R.string.sources_denied_origins), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.sources_origin_candidates_help))
                            installed.deniedOrigins.forEach { request ->
                                SourcePermissionCandidate(request.origin, request.kind.name, permissions, state.busy) { permissions = it }
                            }
                        }
                        OutlinedTextField(permissions, { permissions = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) }, enabled = !state.busy)
                        Button(onClick = { model.saveConfiguration(state.selected!!, configuration.takeIf { hasConfiguration }, permissions) }, enabled = !state.busy) {
                            Text(stringResource(if (hasConfiguration) R.string.sources_save else R.string.sources_save_permissions))
                        }
                        OutlinedButton(onClick = { model.checkUpdate(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_check_update)) }
                        OutlinedButton(onClick = { onDiagnostics(state.selected!!) }, enabled = !state.busy && active) { Text(stringResource(R.string.sources_diagnostics)) }
                        TextButton(onClick = { showDetails = !showDetails }) {
                            Text(stringResource(if (showDetails) R.string.sources_hide_details else R.string.sources_details))
                        }
                        if (showDetails) {
                            Text(stringResource(R.string.sources_format, definition.format), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (definition.profile == EXTENSION_PROFILE) R.string.sources_profile_extension else R.string.sources_profile_standard),
                                style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.sources_revision, definition.revision.toString()), style = MaterialTheme.typography.bodySmall)
                        }
                        if (installed.previous != null) OutlinedButton(onClick = { rollback = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_rollback)) }
                        TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text(stringResource(R.string.sources_delete), color = MaterialTheme.colorScheme.error) }
                    }
                    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.sources_delete)) },
                        text = { Text(stringResource(R.string.sources_delete_help)) }, confirmButton = { TextButton(onClick = { deleting = false; model.remove(state.selected!!) }) { Text(stringResource(R.string.sources_delete)) } },
                        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } })
                    if (rollback) AlertDialog(onDismissRequest = { rollback = false }, title = { Text(stringResource(R.string.sources_rollback)) },
                        text = { Text(stringResource(R.string.sources_rollback_help)) }, confirmButton = { TextButton(onClick = { rollback = false; model.rollback(state.selected!!, permissions) }) { Text(stringResource(R.string.sources_rollback)) } },
                        dismissButton = { TextButton(onClick = { rollback = false }) { Text(stringResource(android.R.string.cancel)) } })
                }
            } else {
                item { Button(onClick = { adding = !adding }, enabled = !state.busy) { Text(stringResource(R.string.sources_add)) } }
                if (adding) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sources_add_help))
                        Text(stringResource(R.string.sources_profile_help))
                        for ((value, label) in listOf(LEGADO_PROFILE to R.string.sources_profile_standard, EXTENSION_PROFILE to R.string.sources_profile_extension)) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { profile = value }) {
                                RadioButton(selected = profile == value, onClick = { profile = value }, enabled = !state.busy)
                                Text(stringResource(label), Modifier.padding(top = 12.dp))
                            }
                        }
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Button(onClick = { model.previewUrl(url, profile) }, enabled = !state.busy && url.isNotBlank()) { Text(stringResource(R.string.sources_preview_url)) }
                        OutlinedButton(onClick = { file.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text(stringResource(R.string.sources_file)) }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp), label = { Text(stringResource(R.string.sources_paste)) })
                        Button(onClick = { model.previewText(text, profile) }, enabled = !state.busy && text.isNotBlank()) { Text(stringResource(R.string.sources_preview)) }
                    }
                }
                item { SectionHeader(text = stringResource(R.string.sources_builtin_group)) }
                item {
                    ListItem(headlineContent = { Text("Z-Library") },
                        supportingContent = { Text(stringResource(R.string.zlibrary_source_summary)) },
                        trailingContent = {
                            if (state.zLibrary.settings.available) IconButton(onClick = { onSearch(ZLibrarySources.ID) }, enabled = !state.busy) {
                                Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                            }
                        }, modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(ZLibrarySources.ID) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                }
                items(state.registry.filter { it.metadata.builtIn && it.metadata.id != ZLibrarySources.ID }, key = { it.metadata.id.toString() }) { entry ->
                    ListItem(headlineContent = { Text(entry.metadata.item.name) }, supportingContent = { Text(stringResource(R.string.sources_builtin)) },
                        modifier = Modifier.clip(MaterialTheme.shapes.large),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        trailingContent = {
                            if (SourceCapability.Search in entry.metadata.capabilities) IconButton(onClick = { onSearch(entry.metadata.id) }) {
                                Icon(painterResource(R.drawable.search_24px), stringResource(R.string.explore_search))
                            }
                        })
                }
                item { SectionHeader(text = stringResource(R.string.sources_imported_group)) }
                if (state.installed.isEmpty()) item { Text(stringResource(R.string.sources_empty)) }
                items(state.installed, key = { it.definition.sourceId }) { source ->
                    val id = ImportedRuleSources.id(source.definition)
                    val entry = state.registry.find { it.metadata.id == id }
                    val available = entry?.metadata?.capabilities?.isNotEmpty() == true
                    val check = state.checks[source.definition.sourceId]
                    ListItem(headlineContent = { Text(source.definition.displayName) },
                        supportingContent = { Column {
                            Text(stringResource(sourceStatus(source, available)))
                            SourceCheckStatus(check, check?.revision == source.definition.contentDigest && check.accountGeneration == entry?.metadata?.accountGeneration)
                            if (!source.hasDiscovery) Text(stringResource(R.string.sources_no_discovery))
                            else if (!source.preferences.discoveryVisible) Text(stringResource(R.string.sources_discovery_hidden))
                            if (source.deniedOrigins.isNotEmpty()) Text(stringResource(R.string.sources_denied_count, source.deniedOrigins.size))
                        } },
                        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy) { model.select(id) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                }
            }
        }
    }
    state.loginForm?.let { form ->
        SourceLoginDialog(form, state.busy, model::submitLogin, model::cancelLogin)
    }
}

private fun sourceStatus(source: InstalledRuleSource, available: Boolean): Int = when {
    !source.preferences.enabled -> if (source.preferences.enabledSetByUser)
        R.string.sources_disabled_by_user else R.string.sources_disabled_by_default
    source.origins.isEmpty() -> R.string.sources_awaiting_permissions
    available -> R.string.sources_available
    else -> R.string.sources_unavailable
}
