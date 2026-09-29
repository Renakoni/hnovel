package indi.renakoni.nextvol.ui.dialog

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.github.michaelbull.result.getOrElse
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.statistics.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AddToBookshelfDialogViewModel @Inject constructor(
    private val bookshelfRepository: BookshelfRepository,
    private val bookRepository: BookRepository,
    private val statsRepository: StatsRepository
) : ViewModel() {
    private val _addToBookshelfDialogUiState = MutableAddToBookshelfDialogUiState()
    private var loadJob: Job? = null
    private var initialBookshelfIds = emptySet<Int>()

    var navController: NavController? = null
    var bookId = ""
        set(value) {
            if (field == value && value.isNotBlank()) return
            field = value
            loadJob?.cancel()
            initialBookshelfIds = emptySet()
            _addToBookshelfDialogUiState.allBookShelf.clear()
            _addToBookshelfDialogUiState.selectedBookshelfIds.clear()
            _addToBookshelfDialogUiState.errorMessage = null
            _addToBookshelfDialogUiState.isLoading = value.isNotBlank()
            if (value.isBlank()) {
                _addToBookshelfDialogUiState.errorMessage = R.string.bookshelf_load_failed
                return
            }
            loadJob = viewModelScope.launch {
                try {
                    val (allBookshelf, selectedBookshelfIds) = withContext(Dispatchers.IO) {
                        val shelves = bookshelfRepository.getAllBookshelfIds()
                            .mapNotNull { bookshelfRepository.getBookshelf(it) }
                        shelves to bookshelfRepository.getBookshelfBookMetadata(value)?.bookShelfIds.orEmpty()
                    }
                    val availableIds = allBookshelf.map { it.id }.toSet()
                    initialBookshelfIds = selectedBookshelfIds.filter { it in availableIds }.toSet()
                    _addToBookshelfDialogUiState.allBookShelf.addAll(allBookshelf)
                    _addToBookshelfDialogUiState.selectedBookshelfIds.addAll(initialBookshelfIds)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to load bookshelves", error)
                    _addToBookshelfDialogUiState.errorMessage = R.string.bookshelf_load_failed
                }
                _addToBookshelfDialogUiState.isLoading = false
            }
        }
    val addToBookshelfDialogUiState: AddToBookshelfDialogUiState = _addToBookshelfDialogUiState

    fun onSelectBookshelf(bookshelfId: Int) {
        if (bookId.isBlank() || _addToBookshelfDialogUiState.isLoading || _addToBookshelfDialogUiState.isSaving) return
        if (_addToBookshelfDialogUiState.allBookShelf.none { it.id == bookshelfId }) return
        if (bookshelfId !in _addToBookshelfDialogUiState.selectedBookshelfIds)
            _addToBookshelfDialogUiState.selectedBookshelfIds.add(bookshelfId)
    }

    fun onDeselectBookshelf(bookshelfId: Int) {
        if (bookId.isBlank() || _addToBookshelfDialogUiState.isLoading || _addToBookshelfDialogUiState.isSaving) return
        _addToBookshelfDialogUiState.selectedBookshelfIds.removeAll { it == bookshelfId }
    }

    fun onDismissAddToBookshelfRequest() {
        if (_addToBookshelfDialogUiState.isSaving) return
        loadJob?.cancel()
        _addToBookshelfDialogUiState.isLoading = false
        _addToBookshelfDialogUiState.selectedBookshelfIds.clear()
        navController?.popBackStack()
    }

    fun processAddToBookshelfRequest() {
        if (bookId.isBlank() || _addToBookshelfDialogUiState.isLoading || _addToBookshelfDialogUiState.isSaving) return
        if (_addToBookshelfDialogUiState.allBookShelf.isEmpty()) return
        val requestedBookId = bookId
        val selectedIds = _addToBookshelfDialogUiState.selectedBookshelfIds.toSet()
        val firstFavorite = initialBookshelfIds.isEmpty() && selectedIds.isNotEmpty()
        _addToBookshelfDialogUiState.isSaving = true
        _addToBookshelfDialogUiState.errorMessage = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    selectedIds.forEach { checkNotNull(bookshelfRepository.getBookshelf(it)) { "Bookshelf no longer exists" } }
                    val oldIds = bookshelfRepository.getBookshelfBookMetadata(requestedBookId)?.bookShelfIds.orEmpty().toSet()
                    val addedIds = selectedIds - oldIds
                    if (addedIds.isNotEmpty()) {
                        val information = bookRepository.getBookInformationFlow(requestedBookId).first()
                            .getOrElse { throw IllegalStateException("Book information is unavailable: ${it.kind}") }
                        addedIds.forEach { bookshelfRepository.addBookIntoBookShelf(it, information) }
                    }
                    (oldIds - selectedIds).forEach { bookshelfRepository.deleteBookFromBookshelf(it, requestedBookId) }
                    check(bookshelfRepository.getBookshelfBookMetadata(requestedBookId)?.bookShelfIds.orEmpty().toSet() == selectedIds) {
                        "Bookshelf selection changed while saving"
                    }
                    if (firstFavorite) statsRepository.markBookFavorited(requestedBookId)
                }
                navController?.popBackStack()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save bookshelf selection", error)
                _addToBookshelfDialogUiState.errorMessage = R.string.save_failed
            } finally {
                _addToBookshelfDialogUiState.isSaving = false
            }
        }
    }

    private companion object {
        const val TAG = "AddToBookshelfDialog"
    }
}
