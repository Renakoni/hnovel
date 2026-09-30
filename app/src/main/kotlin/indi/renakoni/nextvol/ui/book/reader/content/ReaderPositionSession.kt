package indi.renakoni.nextvol.ui.book.reader.content

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle

internal data class ReaderCheckpoint(val bookId: String, val chapterId: String, val position: ReaderPosition? = null)
internal class ReaderPositionRequest(val position: ReaderPosition)

/** One reader session; request and renderer identities reject callbacks from replaced modes. */
internal class ReaderPositionSession(private val savedState: SavedStateHandle = SavedStateHandle()) {
    private var owner: ContentUiState? = null
    private var activeBook = ""
    private var requestedChapter: String? = null
    private class Capture(val token: Any, val read: () -> ReaderPosition?)
    private var capture: Capture? = null
    var checkpoint by mutableStateOf(readCheckpoint())
        private set
    var pending by mutableStateOf<ReaderPositionRequest?>(null)
        private set

    fun owns(state: ContentUiState) = owner === state
    fun ownsRenderer(state: ContentUiState, token: Any) = owns(state) && capture?.token === token

    fun activate(state: ContentUiState, book: String, chapter: String, previous: ReaderCheckpoint?) {
        owner = state
        activeBook = book
        capture = null
        requestedChapter = chapter
        pending = null
        // The settings collector can select a mode before openBook binds the restored route.
        if (book.isBlank() || chapter.isBlank()) return
        val position = previous?.takeIf { it.bookId == book && it.chapterId == chapter }?.position
        save(ReaderCheckpoint(book, chapter, position))
        pending = position?.let(::ReaderPositionRequest)
    }

    fun deactivate() {
        owner = null
        capture = null
        pending = null
    }

    fun captureNow(): ReaderCheckpoint? {
        if (pending == null) owner?.let { state -> capture?.let { publish(state, it.token, it.read()) } }
        return checkpoint
    }

    fun navigate(state: ContentUiState, book: String, chapter: String, preserve: Boolean) {
        if (!owns(state)) return
        val position = if (preserve) {
            // Cancelling a pending adjacent chapter returns to the still-visible old chapter.
            val saved = captureNow()?.position?.takeIf { it.bookId == book && it.chapterId == chapter }
            saved ?: capture?.read?.invoke()?.takeIf { it.bookId == book && it.chapterId == chapter }
        } else null
        activeBook = book
        requestedChapter = chapter
        save(ReaderCheckpoint(book, chapter, position))
        pending = position?.let(::ReaderPositionRequest)
    }

    fun restore(value: ReaderCheckpoint) {
        save(value)
        requestedChapter = value.chapterId
        pending = value.position?.takeIf { owner != null && activeBook == value.bookId }?.let(::ReaderPositionRequest)
    }

    fun register(state: ContentUiState, token: Any, read: () -> ReaderPosition?) {
        if (!owns(state)) return
        capture = Capture(token, read)
        // A new Activity/renderer can mount with the same ViewModel and no pending mode switch.
        // A replaced renderer must not finish a request now owned by its replacement.
        pending = pending?.position?.let(::ReaderPositionRequest)
        if (pending == null) reflow(state)
    }

    fun unregister(state: ContentUiState, token: Any) {
        if (!owns(state) || capture?.token !== token) return
        captureNow()
        capture = null
    }

    fun publish(state: ContentUiState, token: Any, position: ReaderPosition?) {
        if (!owns(state) || capture?.token !== token || pending != null || position == null ||
            position.bookId != activeBook || position.chapterId != state.readingChapterId ||
            requestedChapter?.let { it != position.chapterId } == true) return
        requestedChapter = null
        save(ReaderCheckpoint(position.bookId, position.chapterId, position))
    }

    fun reflow(state: ContentUiState): ReaderPositionRequest? {
        if (!owns(state)) return null
        if (pending == null) {
            pending = checkpoint?.position?.takeIf {
                it.bookId == activeBook && it.chapterId == state.readingChapterId
            }?.let(::ReaderPositionRequest)
        }
        return pending
    }

    fun isCurrent(state: ContentUiState, request: ReaderPositionRequest) = owns(state) && pending === request

    fun finish(state: ContentUiState, request: ReaderPositionRequest, position: ReaderPosition?) {
        if (!isCurrent(state, request)) return
        pending = null
        val accepted = position?.takeIf {
            it.bookId == activeBook && it.chapterId == state.readingChapterId &&
                it.chapterId == checkpoint?.chapterId
        }
        save(checkpoint?.copy(position = accepted))
        if (accepted != null) requestedChapter = null
    }

    /** Explicit bookmark/speech placement supersedes passive and legacy recovery. */
    fun positioned(state: ContentUiState, position: ReaderPosition?) {
        if (!owns(state) || position == null || position.bookId != activeBook ||
            position.chapterId != state.readingChapterId ||
            requestedChapter?.let { it != position.chapterId } == true) return
        pending = null
        requestedChapter = null
        save(ReaderCheckpoint(position.bookId, position.chapterId, position))
    }

    private fun save(value: ReaderCheckpoint?) {
        if (checkpoint == value) return
        checkpoint = value
        if (value == null || value.bookId.isBlank() || value.chapterId.isBlank()) {
            savedState.remove<Bundle>(KEY)
            return
        }
        savedState[KEY] = Bundle().apply {
            putString("book", value.bookId)
            putString("chapter", value.chapterId)
            value.position?.let {
                putInt("component", it.componentIndex)
                putInt("offset", it.offset)
                putString("fingerprint", it.fingerprint)
            }
        }
    }

    private fun readCheckpoint(): ReaderCheckpoint? {
        val data = savedState.get<Bundle>(KEY) ?: return null
        val book = data.getString("book")?.takeIf { it.isNotBlank() } ?: return null
        val chapter = data.getString("chapter")?.takeIf { it.isNotBlank() } ?: return null
        val fingerprint = data.getString("fingerprint")
        val position = if (fingerprint != null && data.containsKey("component") && data.containsKey("offset"))
            ReaderPosition(book, chapter, data.getInt("component"), data.getInt("offset"), fingerprint) else null
        return ReaderCheckpoint(book, chapter, position)
    }

    private companion object { const val KEY = "reader.content.checkpoint" }
}

internal val LocalReaderPositionSession = compositionLocalOf<ReaderPositionSession?> { null }

@Composable
internal fun RegisterReaderPositionCapture(state: ContentUiState, read: () -> ReaderPosition?): Any {
    val session = LocalReaderPositionSession.current
    val active by rememberUpdatedState(LocalReaderRendererActive.current)
    val current by rememberUpdatedState(read)
    val token = remember(session, state) { Any() }
    DisposableEffect(session, state, token, active) {
        if (active) session?.register(state, token) { if (active) current() else null }
        onDispose { session?.unregister(state, token) }
    }
    LaunchedEffect(session, state, token, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { current() }.collect { session?.publish(state, token, it) }
    }
    return token
}
