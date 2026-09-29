package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hnovel.content.LoginField
import hnovel.content.LoginForm
import indi.renakoni.nextvol.R
import kotlinx.coroutines.launch

/** Navigation is local; actions keep the original validated form and field IDs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceConfigurationSheet(
    form: LoginForm?, busy: Boolean,
    onSubmit: (Map<String, String>, String?, String) -> Unit, onCancel: () -> Unit,
    message: String? = null, feedback: List<String> = emptyList(),
) {
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form?.values.orEmpty()) } }
    val fields = form?.fields.orEmpty().filterNot { it.type == "button" &&
        it.action?.trim()?.removeSuffix(";")?.trim() in setOf("login()", "logout()") }
    val sections = fields.mapNotNull { it.section }.distinct()
    var selectedSection by remember { mutableStateOf<String?>(null) }
    val section = selectedSection?.takeIf { it in sections }
    var showFeedback by remember(form?.id, feedback) { mutableStateOf(true) }
    val scrollState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(section) { scrollState.scrollToItem(0) }
    fun back() {
        if (section == null) onCancel() else {
            selectedSection = null
            scope.launch { sheetState.show() }
        }
    }
    ModalBottomSheet(onDismissRequest = ::back, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (section != null) IconButton(onClick = { selectedSection = null }) {
                    Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.import_back))
                }
                Text(section ?: stringResource(R.string.sources_configuration),
                    Modifier.weight(1f).padding(start = if (section == null) 12.dp else 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onCancel) {
                    Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scrollState,
                contentPadding = PaddingValues(vertical = 8.dp)) {
                if (form == null) item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                message?.let { item { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
                items(fields.filter { it.section == section }, key = { it.id }) { field ->
                    SourceConfigurationField(field, values[field.name].orEmpty(), !busy && field.enabled,
                        onChange = { values[field.name] = it },
                        onAction = { form?.let { onSubmit(values.toMap(), field.id, it.id) } })
                }
                if (section == null && sections.isNotEmpty()) {
                    item { HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant) }
                    items(sections) { name ->
                        ListItem(headlineContent = { Text(name) },
                            trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.clickable { selectedSection = name }.padding(horizontal = 8.dp))
                    }
                }
            }
            if (feedback.isNotEmpty() && showFeedback) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sources_configuration_result), Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { showFeedback = false }, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(R.drawable.close_24px), stringResource(R.string.sources_configuration_dismiss_result), Modifier.size(18.dp))
                        }
                    }
                    SelectionContainer {
                        Text(feedback.joinToString("\n\n"), Modifier.fillMaxWidth().heightIn(max = 112.dp)
                            .verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceConfigurationField(field: LoginField, value: String, enabled: Boolean,
    onChange: (String) -> Unit, onAction: () -> Unit) {
    // Decorative emoji are removed from display only, never from input bindings.
    val label = field.label.dropWhile { !it.isLetterOrDigit() }.ifBlank { field.label }
    val supporting: (@Composable () -> Unit)? = field.description?.let { { Text(it) } }
    val row = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    val checked = field.checked
    when (field.type) {
        "button" -> if (checked != null) {
            ListItem(headlineContent = { Text(label) }, supportingContent = supporting,
                trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
                colors = colors, modifier = row.toggleable(checked, enabled = enabled, role = Role.Switch,
                    onValueChange = { onAction() }).padding(horizontal = 8.dp))
        } else {
            ListItem(headlineContent = { Text(label) }, supportingContent = supporting, colors = colors,
                modifier = row.clickable(enabled = enabled, role = Role.Button, onClick = onAction).padding(horizontal = 8.dp))
        }
        "toggle", "select" -> {
            var expanded by remember(field.id) { mutableStateOf(false) }
            Box {
                ListItem(headlineContent = { Text(label) }, supportingContent = supporting,
                    trailingContent = { Text(value, color = MaterialTheme.colorScheme.primary) }, colors = colors,
                    modifier = row.clickable(enabled = enabled) { expanded = true }.padding(horizontal = 8.dp))
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    field.choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice) }, enabled = enabled, onClick = {
                            expanded = false
                            onChange(choice)
                            if (field.action != null) onAction()
                        })
                    }
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            OutlinedTextField(value, { onChange(it.take(4096)) }, label = { Text(label) },
                supportingText = supporting, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text))
            if (field.action != null) TextButton(onClick = onAction, enabled = enabled) {
                Text(stringResource(R.string.sources_apply_field))
            }
        }
    }
}
