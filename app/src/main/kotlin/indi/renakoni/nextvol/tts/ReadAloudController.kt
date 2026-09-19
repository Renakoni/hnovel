package indi.renakoni.nextvol.tts

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.book.BookIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeechAction { Start, Pause, Resume, Stop, Previous, Next, PreviousChapter, NextChapter }

/** UI sends explicit commands. Merely observing this state never starts a service or an engine. */
@Singleton
class ReadAloudController @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutableState = MutableStateFlow(ReadAloudState())
    val state = mutableState.asStateFlow()

    fun start(bookId: String, chapterId: String) {
        val book = BookIdentity.book(bookId)
        val chapter = BookIdentity.chapter(chapterId, book)
        val current = state.value
        if (current.request?.bookId == book.storageKey && current.phase !in setOf(SpeechPhase.Stopped, SpeechPhase.Completed)) {
            if (current.phase in setOf(SpeechPhase.Paused, SpeechPhase.Failed)) command(SpeechAction.Resume)
            return
        }
        start(SpeechRequest(book.storageKey, chapter.storageKey))
    }

    fun preview(text: String) = start(SpeechRequest("", "", text))

    private fun start(request: SpeechRequest) {
        mutableState.value = ReadAloudState(request, SpeechPhase.Preparing)
        send(SpeechAction.Start, request)
    }

    fun command(action: SpeechAction) = send(action, state.value.request)
    internal fun publish(state: ReadAloudState) { mutableState.value = state }

    private fun send(action: SpeechAction, request: SpeechRequest?) {
        if (request == null) return
        try {
            val intent = intent(context, action, request)
            if (action == SpeechAction.Start || action == SpeechAction.Resume) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        } catch (_: IllegalStateException) {
            mutableState.value = state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.ServiceUnavailable)
        } catch (_: SecurityException) {
            mutableState.value = state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.ServiceUnavailable)
        }
    }

    companion object {
        internal const val ACTION = "speech.action"
        internal const val REQUEST = "speech.request"
        const val OPEN_PLAYER = "indi.renakoni.nextvol.OPEN_READ_ALOUD"
        internal fun intent(context: Context, action: SpeechAction, request: SpeechRequest): Intent =
            Intent(context, ReadAloudService::class.java).putExtra(ACTION, action.name)
                .putExtra(REQUEST, Json.encodeToString(request))
    }
}
