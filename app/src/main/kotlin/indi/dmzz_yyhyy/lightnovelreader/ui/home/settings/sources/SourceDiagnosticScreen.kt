package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class DiagnosticState(val busy: Boolean = false, val report: SourceDiagnosticReport? = null, val failed: Boolean = false)

@HiltViewModel
class SourceDiagnosticViewModel @Inject constructor(private val diagnostics: SourceDiagnostics,
    @ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(DiagnosticState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun run(id: Identifier, stage: DiagnosticStage, keyword: String, book: String, chapter: String) {
        if (state.value.busy) return
        mutable.value = DiagnosticState(busy = true)
        job = viewModelScope.launch {
            try { mutable.value = DiagnosticState(report = diagnostics.run(id, stage, keyword, book, chapter)) }
            catch (cancelled: CancellationException) { mutable.value = DiagnosticState(); throw cancelled }
            catch (_: Exception) { mutable.value = DiagnosticState(failed = true) }
        }
    }
    fun cancel() { job?.cancel() }
    fun export(uri: Uri) {
        val report = state.value.report ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(report.export()) } ?: error("Export unavailable") }
            catch (_: Exception) { mutable.update { it.copy(failed = true) } }
        }
    }
}

fun NavGraphBuilder.sourceDiagnosticDestination() {
    composable<Route.Main.Settings.SourceDiagnostic> { entry ->
        val route = entry.toRoute<Route.Main.Settings.SourceDiagnostic>()
        val nav = LocalNavController.current
        val model = hiltViewModel<SourceDiagnosticViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::export) }
        var keyword by remember { mutableStateOf("") }
        var book by remember { mutableStateOf("") }
        var chapter by remember { mutableStateOf("") }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TextButton(onClick = { model.cancel(); nav.popBackStack() }) { Text(stringResource(R.string.sources_back)) } }
            item { Text(stringResource(R.string.sources_diagnostics), style = MaterialTheme.typography.headlineSmall) }
            item { Text(stringResource(R.string.source_diagnostics_help)) }
            item { OutlinedTextField(keyword, { keyword = it }, label = { Text(stringResource(R.string.source_diagnostics_keyword)) }, enabled = !state.busy) }
            item { OutlinedTextField(book, { book = it }, label = { Text(stringResource(R.string.source_diagnostics_book)) }, enabled = !state.busy) }
            item { OutlinedTextField(chapter, { chapter = it }, label = { Text(stringResource(R.string.source_diagnostics_chapter)) }, enabled = !state.busy) }
            item {
                Column {
                    DiagnosticStage.entries.forEach { stage ->
                        OutlinedButton(onClick = { model.run(Identifier(route.namespace, route.sourceId), stage, keyword, book, chapter) }, enabled = !state.busy) {
                            Text(stringResource(when (stage) {
                                DiagnosticStage.Search -> R.string.source_diagnostics_search
                                DiagnosticStage.Information -> R.string.source_diagnostics_information
                                DiagnosticStage.Directory -> R.string.source_diagnostics_directory
                                DiagnosticStage.Content -> R.string.source_diagnostics_content
                                DiagnosticStage.Discovery -> R.string.source_diagnostics_discovery
                            }))
                        }
                    }
                }
            }
            if (state.busy) item { LinearProgressIndicator(); TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) } }
            if (state.failed) item { Text(stringResource(R.string.sources_action_failed)) }
            state.report?.let { report ->
                item { Text("${report.result} · ${report.field.orEmpty()} · ${report.count}") }
                item { TextButton(onClick = { export.launch("source-diagnostic.json") }) { Text(stringResource(R.string.source_diagnostics_export)) } }
                item { Text(report.export(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
