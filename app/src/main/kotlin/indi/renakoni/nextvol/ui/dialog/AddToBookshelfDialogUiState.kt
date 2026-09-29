package indi.renakoni.nextvol.ui.dialog

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf

interface AddToBookshelfDialogUiState {
    val allBookShelf: List<Bookshelf>
    val selectedBookshelfIds: List<Int>
    val isLoading: Boolean
    val isSaving: Boolean
    val errorMessage: Int?
}

class MutableAddToBookshelfDialogUiState: AddToBookshelfDialogUiState {
    override var allBookShelf = mutableStateListOf<Bookshelf>()
    override var selectedBookshelfIds  = mutableStateListOf<Int>()
    override var isLoading by mutableStateOf(true)
    override var isSaving by mutableStateOf(false)
    override var errorMessage by mutableStateOf<Int?>(null)
}
