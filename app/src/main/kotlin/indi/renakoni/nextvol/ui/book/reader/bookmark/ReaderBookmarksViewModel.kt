package indi.renakoni.nextvol.ui.book.reader.bookmark

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.data.bookmark.ReadingBookmarkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderBookmarksViewModel @Inject constructor(private val repository: ReadingBookmarkRepository) : ViewModel() {
    var bookmarks by mutableStateOf<List<ReadingBookmark>>(emptyList())
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var book: SourceBookId? = null
    private var observer: Job? = null

    fun open(bookId: String) {
        val next = SourceBookId.fromStorageKey(bookId)
        if (book == next) return
        book = next
        bookmarks = emptyList()
        notice = null
        observer?.cancel()
        observer = viewModelScope.launch {
            try {
                repository.observe(next).collect { if (book == next) bookmarks = it }
            } catch (failure: CancellationException) { throw failure
            } catch (failure: Exception) {
                Log.e("ReadingBookmark", "Cannot load bookmarks", failure)
                if (book == next) notice = R.string.reader_bookmarks_failed
            }
        }
    }

    fun clearNotice() { notice = null }

    fun add(bookmark: ReadingBookmark) = write { current ->
        if (bookmark.bookId != current.storageKey) return@write
        val added = repository.add(bookmark)
        if (book == current) notice = if (added) R.string.reader_bookmarks_added else R.string.reader_bookmarks_duplicate
    }

    fun delete(bookmark: ReadingBookmark) = write { current ->
        repository.delete(current, bookmark.id)
    }

    private fun write(action: suspend (SourceBookId) -> Unit) {
        val current = book ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { action(current)
            } catch (failure: CancellationException) { throw failure
            } catch (failure: Exception) {
                Log.e("ReadingBookmark", "Cannot write bookmark", failure)
                if (book == current) notice = R.string.reader_bookmarks_failed
            } finally { busy = false }
        }
    }
}
