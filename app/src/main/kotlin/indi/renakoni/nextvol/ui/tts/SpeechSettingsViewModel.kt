package indi.renakoni.nextvol.ui.tts

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.tts.ReadAloudController
import indi.renakoni.nextvol.tts.SpeechEngine
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.SpeechVoice
import indi.renakoni.nextvol.tts.SystemSpeechEngines
import indi.renakoni.nextvol.tts.HttpSpeechRepository
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.speechError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class SpeechSettingsUiState(
    val settings: SpeechSettings = SpeechSettings(),
    val engines: List<SpeechEngine> = emptyList(),
    val voices: List<SpeechVoice> = emptyList(),
    val loading: Boolean = true,
    val error: SpeechError? = null,
    val httpSources: List<SavedHttpSpeechSource> = emptyList(),
)

@HiltViewModel
class SpeechSettingsViewModel @Inject constructor(
    private val repository: SpeechSettingsRepository,
    private val engines: SystemSpeechEngines,
    val controller: ReadAloudController,
    private val http: HttpSpeechRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechSettingsUiState())
    val state = mutableState.asStateFlow()
    private var catalog: Job? = null
    private var request = 0L

    init {
        viewModelScope.launch {
            var lastEngine: Pair<String, String?>? = null
            repository.changes.collect { settings ->
                mutableState.update { it.copy(settings = settings) }
                val selection = settings.engine to settings.httpSource
                if (lastEngine != selection) {
                    lastEngine = selection
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val engine = state.value.settings.engine
        val current = ++request
        catalog?.cancel()
        catalog = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, voices = emptyList(), error = null) }
            try {
                val selected = state.value.settings.httpSource
                if (selected != null) {
                    val sources = http.sources()
                    mutableState.update { it.copy(httpSources = sources) }
                    val source = sources.find { it.definition.id == selected }
                        ?: throw indi.renakoni.nextvol.tts.SpeechException(SpeechError.HttpSourceUnavailable)
                    if (!source.isConfigured) throw indi.renakoni.nextvol.tts.SpeechException(SpeechError.HttpLogin)
                    return@launch
                }
                val installed = engines.installed()
                mutableState.update { it.copy(engines = installed) }
                val details = engines.inspect(engine)
                if (current == request) mutableState.update { it.copy(voices = details.voices) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current == request) mutableState.update { it.copy(error = failure.speechError(
                    if (it.settings.httpSource == null) SpeechError.EngineUnavailable else SpeechError.Storage)) }
            } finally {
                if (current == request) mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun selectEngine(engine: String) = update { it.copy(engine = engine, voice = "", httpSource = null) }
    fun selectVoice(voice: String) = update { it.copy(voice = voice) }
    fun setRate(value: Float?) = update { it.copy(rate = value) }
    fun setPitch(value: Float?) = update { it.copy(pitch = value) }

    private fun update(change: (SpeechSettings) -> SpeechSettings) {
        viewModelScope.launch {
            try { repository.update(change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }
}
