package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.SourceCapability
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.LoginStatus
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.json.*

fun NavGraphBuilder.settingsSourcesDestination() {
    composable<Route.Main.Settings.Sources> {
        val nav = LocalNavController.current
        val model = hiltViewModel<SourcesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        SourcesScreen(state, model) { nav.popBackStack() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(state: SourceManagementState, model: SourcesViewModel, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf(false) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::previewFile) }
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
        TopAppBar(title = { Text(installed?.definition?.displayName ?: stringResource(R.string.sources_title)) },
            navigationIcon = { IconButton(onClick = { back() }) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) } }
            state.message?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.primary) } }
            if (state.preview != null) {
                item { Preview(state, model) }
            } else if (installed != null) {
                item {
                    val definition = installed.definition
                    val raw = remember(definition) { Json.parseToJsonElement(definition.rawJson).jsonObject }
                    var configuration by remember(definition.contentDigest, state.variable) { mutableStateOf(state.variable) }
                    var permissions by remember(installed) { mutableStateOf(installed.origins.joinToString("\n") { it.origin }) }
                    val variableLabel = raw["variableComment"]?.jsonPrimitive?.content.orEmpty().ifBlank { stringResource(R.string.sources_configuration) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.sources_format, definition.format))
                        Text(stringResource(R.string.sources_revision, definition.revision.toString()))
                        Text(stringResource(when (state.loginStatus) {
                            LoginStatus.Authenticated -> R.string.sources_logged_in
                            LoginStatus.Required -> R.string.sources_login_required
                            LoginStatus.LoggedOut -> R.string.sources_logged_out
                        }))
                        if (state.registry.any { it.metadata.id == state.selected && SourceCapability.Login in it.metadata.capabilities }) {
                            Button(onClick = { model.beginLogin(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_login)) }
                            OutlinedButton(onClick = { model.logout(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_logout)) }
                        }
                        OutlinedTextField(configuration, { configuration = it }, Modifier.fillMaxWidth(), label = { Text(variableLabel) }, enabled = !state.busy)
                        Text(stringResource(R.string.sources_permissions_help))
                        OutlinedTextField(permissions, { permissions = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) }, enabled = !state.busy)
                        Button(onClick = { model.saveConfiguration(state.selected!!, configuration, permissions) }, enabled = !state.busy) { Text(stringResource(R.string.sources_save)) }
                        OutlinedButton(onClick = { model.checkUpdate(state.selected!!) }, enabled = !state.busy) { Text(stringResource(R.string.sources_check_update)) }
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
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Button(onClick = { model.previewUrl(url) }, enabled = !state.busy && url.isNotBlank()) { Text(stringResource(R.string.sources_preview_url)) }
                        OutlinedButton(onClick = { file.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text(stringResource(R.string.sources_file)) }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp), label = { Text(stringResource(R.string.sources_paste)) })
                        Button(onClick = { model.previewText(text) }, enabled = !state.busy && text.isNotBlank()) { Text(stringResource(R.string.sources_preview)) }
                    }
                }
                if (state.installed.isEmpty()) item { Text(stringResource(R.string.sources_empty)) }
                items(state.registry.filter { it.metadata.builtIn }, key = { it.metadata.id.toString() }) { entry ->
                    ListItem(headlineContent = { Text(entry.metadata.item.name) }, supportingContent = { Text(stringResource(R.string.sources_builtin)) })
                }
                items(state.installed, key = { it.definition.sourceId }) { source ->
                    val id = ImportedRuleSources.id(source.definition)
                    val available = state.registry.any { it.metadata.id == id && it.metadata.capabilities.isNotEmpty() }
                    ListItem(headlineContent = { Text(source.definition.displayName) },
                        supportingContent = { Text(stringResource(if (available) R.string.sources_available else R.string.sources_unavailable)) },
                        modifier = Modifier.clickable(enabled = !state.busy) { model.select(id) })
                }
            }
        }
    }
    state.loginForm?.let { form ->
        val values = remember(form) { mutableStateMapOf<String, String>() }
        AlertDialog(onDismissRequest = model::cancelLogin, title = { Text(stringResource(R.string.sources_login)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form.browserUrl != null) Text(stringResource(R.string.sources_browser_login))
                form.fields.forEach { field ->
                    if (field.type == "button") OutlinedButton(onClick = { model.submitLogin(values.toMap(), field.name) }, enabled = !state.busy) { Text(field.name) }
                    else OutlinedTextField(values[field.name].orEmpty(), { values[field.name] = it }, label = { Text(field.name) }, enabled = !state.busy,
                        visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text))
                }
            } }, confirmButton = { TextButton(onClick = { model.submitLogin(values.toMap()) }, enabled = !state.busy) { Text(stringResource(R.string.sources_login)) } },
            dismissButton = { TextButton(onClick = model::cancelLogin) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable
private fun Preview(state: SourceManagementState, model: SourcesViewModel) {
    val preview = state.preview!!
    val selected = remember(preview) { mutableStateMapOf<Int, Boolean>() }
    val permissions = remember(preview) { mutableStateMapOf<Int, String>() }
    var approveIdentity by remember(preview) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sources_preview), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.sources_permissions_help))
        preview.issues.forEach { Text(stringResource(R.string.sources_import_problem, it.code.name, it.field.orEmpty())) }
        preview.candidates.forEach { candidate ->
            if (candidate.index !in permissions) permissions[candidate.index] = runCatching { SourcesViewModel.origin(candidate.importKey) }.getOrDefault("")
            Row {
                Checkbox(selected[candidate.index] == true, { selected[candidate.index] = it }, enabled = !state.busy && candidate.enabled)
                Column(Modifier.weight(1f)) {
                    Text(candidate.displayName)
                    Text(stringResource(if (candidate.existing == null) R.string.sources_new else R.string.sources_replace))
                    if (!candidate.enabled) Text(stringResource(R.string.sources_disabled_definition))
                }
            }
            if (selected[candidate.index] == true) OutlinedTextField(permissions[candidate.index].orEmpty(), { permissions[candidate.index] = it },
                Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) }, enabled = !state.busy)
        }
        if (state.updateTarget != null) Row {
            Checkbox(approveIdentity, { approveIdentity = it }, enabled = !state.busy)
            Text(stringResource(R.string.sources_identity_approval), Modifier.weight(1f))
        }
        Button(onClick = { model.commit(selected.filterValues { it }.keys.toSet(), permissions.toMap(), approveIdentity) },
            enabled = !state.busy && selected.values.any { it }) { Text(stringResource(R.string.sources_apply)) }
        TextButton(onClick = model::dismissPreview, enabled = !state.busy) { Text(stringResource(android.R.string.cancel)) }
    }
}
