package indi.renakoni.nextvol.ui.book.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.rules.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal data class PixivBlockingState(
    val book: PixivBlockBook? = null,
    val rules: List<PixivBlockRule> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
    val confirmation: PixivBlockKind? = null,
    val message: Int? = null,
) {
    fun contains(kind: PixivBlockKind, value: String) = rules.any { it.kind == kind && it.value == value }
}

@HiltViewModel
internal class PixivBlockingViewModel @Inject constructor(private val blocking: PixivBlocking) : ViewModel() {
    var state by mutableStateOf(PixivBlockingState())
        private set
    private var source: Identifier? = null
    private var generation = 0

    suspend fun loadBook(book: SourceBookId) = load(book.sourceId, book)
    suspend fun loadManager(id: Identifier) = load(id, null)

    private suspend fun load(id: Identifier, bookId: SourceBookId?) {
        val current = ++generation
        source = id
        state = PixivBlockingState(busy = true)
        try {
            val loaded = withContext(Dispatchers.IO) {
                val book = bookId?.let { blocking.book(it) }
                PixivBlockingState(book = book, rules =
                    if (bookId == null || book != null) blocking.rules(id) else emptyList())
            }
            if (current == generation) state = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (current == generation) state = PixivBlockingState(failed = true)
        }
    }

    fun request(kind: PixivBlockKind) {
        if (!state.busy && state.book != null) state = state.copy(confirmation = kind)
    }
    fun cancel() { if (!state.busy) state = state.copy(confirmation = null) }
    fun consumeMessage() { state = state.copy(message = null) }

    fun confirm(tags: Set<String>) {
        val book = state.book ?: return
        val rows = when (state.confirmation) {
            PixivBlockKind.Book -> listOf(PixivBlockRule(PixivBlockKind.Book, book.key, book.title))
            PixivBlockKind.Author -> if (book.authorId.isNotBlank())
                listOf(PixivBlockRule(PixivBlockKind.Author, book.authorId, book.author.ifBlank { book.authorId })) else emptyList()
            PixivBlockKind.Tag -> book.tags.filter { it in tags }.map { PixivBlockRule(PixivBlockKind.Tag, it, it) }
            else -> emptyList()
        }.filterNot { state.contains(it.kind, it.value) }
        if (rows.isNotEmpty()) change(R.string.pixiv_block_added) { blocking.add(it, rows) }
    }

    fun remove(rule: PixivBlockRule) = change(R.string.pixiv_block_removed) { blocking.remove(it, rule) }

    private fun change(message: Int, action: suspend (Identifier) -> Unit) {
        val id = source ?: return
        if (state.busy) return
        val current = generation
        state = state.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val rules = withContext(Dispatchers.IO) { action(id); blocking.rules(id) }
                if (current == generation) state = state.copy(rules = rules, confirmation = null, message = message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current == generation) state = state.copy(message = R.string.sources_action_failed)
            } finally {
                if (current == generation) state = state.copy(busy = false)
            }
        }
    }
}
