package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.tts.ReadAloudController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReadAloudOverlayViewModel @Inject constructor(
    val controller: ReadAloudController,
    books: LocalBookDataSource,
) : ViewModel() {
    // Listening already loaded this metadata; changing pages must not refresh the source.
    val book = controller.state.map { it.request?.takeUnless { request -> request.isPreview }?.bookId }
        .distinctUntilChanged()
        .mapLatest { id -> id?.let { books.getBookInformation(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
