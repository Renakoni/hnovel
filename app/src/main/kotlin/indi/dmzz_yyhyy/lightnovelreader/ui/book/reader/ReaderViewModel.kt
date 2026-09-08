package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.ContentComponentRepository
import indi.dmzz_yyhyy.lightnovelreader.data.reading.RepositoryReaderRecordStore
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.FlipPageContentViewModel
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    statsRepository: StatsRepository,
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository,
    val contentComponentRepository: ContentComponentRepository
) : ViewModel() {
    private val settingState = SettingState(userDataRepository, viewModelScope)
    val readerSettings: ReaderSettingsEditor = settingState
    val fontFamilySettings: ReaderFontFamilySettings = settingState.fontFamilySettings
    private var contentViewModel: ContentViewModel? by mutableStateOf(null)
    private val _uiState = MutableReaderScreenUiState(contentViewModel?.uiState)
    val uiState: ReaderScreenUiState = _uiState
    private val statisticsScope = CoroutineScope(Dispatchers.IO)
    private val readingRecords = ReaderReadingRecords(
        store = RepositoryReaderRecordStore(bookRepository, statsRepository, userDataRepository),
        scope = viewModelScope,
        statisticsScope = statisticsScope,
        currentBookId = { bookId },
        currentChapterTitle = {
            _uiState.contentUiState?.readingChapterContent?.map { it.title }?.getOrElse { null }
        },
        chapterCount = {
            _uiState.bookVolumes?.map { volumes ->
                volumes.volumes.sumOf { it.chapters.size }
            }?.getOrElse { 0 } ?: 0
        },
    )
    var bookId = ""
        set(value) {
            field = value
            _uiState.bookId = value
            contentViewModel?.changeBookId(value)
            readingRecords.openBook(value)

            viewModelScope.launch(Dispatchers.IO) {
                bookRepository.getBookVolumesFlow(value).collect {
                    _uiState.bookVolumes = it
                }
            }
        }
    private var chapterId = ""

    init {
        viewModelScope.launch {
            settingState.isUsingFlipPageUserData.getFlowWithDefault(false).collect {
                if (it && contentViewModel !is FlipPageContentViewModel) {
                    contentViewModel = FlipPageContentViewModel(
                        bookRepository = bookRepository,
                        coroutineScope = viewModelScope,
                        updateReadingProgress = ::saveReadingProgress,
                        contentComponentRepository = contentComponentRepository
                    )
                    contentViewModel?.changeBookId(bookId)
                    contentViewModel?.changeChapter(chapterId)
                    _uiState.contentUiState = contentViewModel?.uiState
                }
                else if (!it && contentViewModel !is ScrollContentViewModel) {
                    contentViewModel = ScrollContentViewModel(
                        bookRepository = bookRepository,
                        coroutineScope = viewModelScope,
                        settingState = settingState,
                        updateReadingProgress = ::saveReadingProgress,
                        contentComponentRepository = contentComponentRepository
                    )
                    contentViewModel?.changeBookId(bookId)
                    contentViewModel?.changeChapter(chapterId)
                    _uiState.contentUiState = contentViewModel?.uiState
                }
            }
        }
    }

    fun prevChapter() = contentViewModel?.loadPrevChapter()

    fun nextChapter() = contentViewModel?.loadNextChapter()

    fun changeChapter(chapterId: String) {
        this.chapterId = chapterId
        contentViewModel?.changeChapter(chapterId)
    }

    private fun saveReadingProgress(chapterId: String, progress: Float) =
        readingRecords.saveProgress(chapterId, progress)

    fun updateTotalReadingTime(bookId: String, totalReadingTime: Int) =
        readingRecords.updateTotalReadingTime(bookId, totalReadingTime)

    fun accumulateReadingTime(bookId: String, seconds: Int) =
        readingRecords.accumulateReadingTime(bookId, seconds)
}
