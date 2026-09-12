package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import indi.dmzz_yyhyy.lightnovelreader.R

/** Renders validated source fields. Scripts and account lifetime remain outside Compose. */
@Composable
internal fun SourceLoginDialog(form: LoginForm, busy: Boolean,
    onSubmit: (Map<String, String>, String?) -> Unit, onCancel: () -> Unit) {
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form.values) } }
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.sources_login)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.browserUrl != null) Text(stringResource(R.string.sources_browser_login))
            form.fields.forEach { field ->
                fun change(value: String) {
                    values[field.name] = value
                    if (field.action != null) onSubmit(values.toMap(), field.name)
                }
                when (field.type) {
                    "button" -> OutlinedButton(onClick = { onSubmit(values.toMap(), field.name) }, enabled = !busy) { Text(field.label) }
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
                        if (field.action != null) TextButton(onClick = { onSubmit(values.toMap(), field.name) }, enabled = !busy) {
                            Text(stringResource(R.string.sources_apply_field))
                        }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { onSubmit(values.toMap(), null) }, enabled = !busy) { Text(stringResource(R.string.sources_login)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } })
}
