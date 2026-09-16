package indi.renakoni.nextvol.ui.book.reader

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.utils.loadReaderTypeface
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReaderFontEntry(settings: ReaderSettingsEditor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            var imported: File? = null
            var committed = false
            try {
                withContext(Dispatchers.IO) {
                    // A distinct URI invalidates resolved fonts, even when replacing a custom font.
                    val file = File.createTempFile("reader-font-", ".font", context.filesDir)
                    imported = file
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    loadReaderTypeface(file)
                    currentCoroutineContext().ensureActive()
                    val previous = settings.fontFamilyUri
                    // Do not delete an already-persisted font if cancellation arrives at the write boundary.
                    withContext(NonCancellable) {
                        settings.fontFamilyUriUserData.set(file.toUri())
                        committed = true
                    }
                    // Resolved custom fonts own a Typeface, so old requests no longer read this file.
                    deleteReplacedReaderFont(context, previous)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.font_file_error, Toast.LENGTH_SHORT).show()
            } finally {
                if (!committed) imported?.delete()
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val selected = ReaderFont.entries.firstOrNull { it.uri == settings.fontFamilyUri }
    var showLicenses by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { font ->
                val active = selected == font
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier.weight(1f).heightIn(min = 108.dp).clip(shape)
                        .background(if (active) colors.secondaryContainer else colors.surfaceContainerHigh)
                        .border(1.dp, if (active) colors.primary else colors.outlineVariant, shape)
                        .selectable(active, role = Role.RadioButton) {
                            scope.launch(Dispatchers.IO) {
                                val previous = settings.fontFamilyUri
                                withContext(NonCancellable) {
                                    settings.fontFamilyUriUserData.set(font.uri)
                                    deleteReplacedReaderFont(context, previous)
                                }
                            }
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.reader_font_sample), fontFamily = remember(font) { font.family() },
                        fontSize = 24.sp, color = if (active) colors.onSecondaryContainer else colors.onSurface)
                    Text(stringResource(font.title), style = MaterialTheme.typography.labelMedium,
                        color = if (active) colors.onSecondaryContainer else colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(stringResource(selected?.description ?: R.string.reader_font_imported_description),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { picker.launch("*/*") }) {
                Text(stringResource(if (selected == null) R.string.reader_font_replace else R.string.reader_font_import))
            }
            TextButton(onClick = { showLicenses = true }) { Text(stringResource(R.string.reader_font_licenses)) }
        }
    }
    if (showLicenses) {
        val license = remember {
            listOf("source-han-serif.txt", "lxgw-wenkai.txt").joinToString("\n\n") { name ->
                context.assets.open("font-licenses/$name").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.reader_font_licenses)) },
            text = { Text(license, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.confirm)) } },
        )
    }
}

private fun deleteReplacedReaderFont(context: Context, uri: Uri) {
    val file = File(uri.path ?: return)
    if (file.parentFile == context.filesDir && file.name.startsWith("reader-font-")) file.delete()
}
