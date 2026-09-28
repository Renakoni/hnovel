package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hnovel.content.LoginForm
import indi.renakoni.nextvol.R

/** Renders validated source fields. Scripts and account lifetime remain outside Compose. */
@Composable
internal fun SourceLoginDialog(form: LoginForm, busy: Boolean,
    onSubmit: (Map<String, String>, String?, String) -> Unit, onCancel: () -> Unit,
    title: String = stringResource(R.string.sources_login), message: String? = null,
    showLoginAction: Boolean = true, feedback: List<String> = emptyList()) {
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form.values) } }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message?.let { Text(it) }
            if (feedback.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer {
                        Text(feedback.joinToString("\n\n"), Modifier.heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()).padding(12.dp))
                    }
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.browserUrl != null) Text(stringResource(R.string.sources_browser_login))
            form.fields.forEach { field -> key(field.id) {
                fun change(value: String) {
                    values[field.name] = value
                    if (field.action != null) onSubmit(values.toMap(), field.id, form.id)
                }
                when (field.type) {
                    "button" -> OutlinedButton(onClick = { onSubmit(values.toMap(), field.id, form.id) }, enabled = !busy) { Text(field.label) }
                    "toggle" -> OutlinedButton(onClick = {
                        change(field.choices[(field.choices.indexOf(values[field.name]) + 1) % field.choices.size])
                    }, enabled = !busy) { Text("${field.label}: ${values[field.name].orEmpty()}") }
                    "select" -> {
                        var expanded by remember(field.name) { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text("${field.label}: ${values[field.name].orEmpty()}") }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                field.choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, enabled = !busy, onClick = { expanded = false; change(choice) }) }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(values[field.name].orEmpty(), { values[field.name] = it.take(4096) },
                            label = { Text(field.label) }, enabled = !busy,
                            visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text))
                        if (field.action != null) TextButton(onClick = { onSubmit(values.toMap(), field.id, form.id) }, enabled = !busy) {
                            Text(stringResource(R.string.sources_apply_field))
                        }
                    }
                }
            } }
            }
        } }, confirmButton = {
            if (showLoginAction) TextButton(onClick = { onSubmit(values.toMap(), null, form.id) }, enabled = !busy) { Text(stringResource(R.string.sources_login)) }
        }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(if (showLoginAction) android.R.string.cancel else android.R.string.ok)) } })
}
