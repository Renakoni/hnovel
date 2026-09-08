package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookReadingDataAccess
import indi.dmzz_yyhyy.lightnovelreader.data.book.ChapterSource
import indi.dmzz_yyhyy.lightnovelreader.data.reading.RepositoryReaderRecordStore
import indi.dmzz_yyhyy.lightnovelreader.data.statistics.StatsRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderMode
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeFactory
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ReaderModeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    statsRepository: StatsRepository,
    private val chapterSource: ChapterSource,
    private val readingData: BookReadingDataAccess,
    userDataRepository: UserDataRepository,
    private val modeFactory: ReaderModeFactory
) : ViewModel() {
    private val settingState = SettingState(userDataRepository, viewModelScope)
    val readerSettings: ReaderSettingsEditor = settingState
    val fontFamilySettings: ReaderFontFamilySettings = settingState.fontFamilySettings
    private val modeHost: ReaderModeHost = ReaderModeHost { mode ->
        modeFactory.create(mode, viewModelScope, settingState.continuousScrollSettings, ::saveReadingProgress)
    }
    private val _uiState = MutableReaderScreenUiState(modeHost.uiState)
    val uiState: ReaderScreenUiState = _uiState
    private val statisticsScope = CoroutineScope(Dispatchers.IO)
    private val chapterCountsByBook = ConcurrentHashMap<String, Int>()
    private val readingRecords = ReaderReadingRecords(
        store = RepositoryReaderRecordStore(readingData, statsRepository, userDataRepository),
        scope = viewModelScope,
        statisticsScope = statisticsScope,
        currentBookId = { bookId },
        currentChapterTitle = {
            _uiState.contentUiState?.readingChapterContent?.map { it.title }?.getOrElse { null }
        },
        chapterCount = { id -> chapterCountsByBook[id] ?: 0 },
    )
    private var bookVolumesJob: Job? = null
    private var bookVolumesRequest = 0L
    @set:MainThread
    var bookId = ""
        set(value) {
            field = value
            _uiState.bookId = value
            modeHost.changeBookId(value)
            readingRecords.openBook(value)

            bookVolumesJob?.cancel()
            val request = ++bookVolumesRequest
            _uiState.bookVolumes = null
            bookVolumesJob = viewModelScope.launch(Dispatchers.IO) {
                chapterSource.getBookVolumesFlow(value).collect {
                    withContext(Dispatchers.Main.immediate) {
                        if (request == bookVolumesRequest) {
                            it.map { volumes ->
                                val count = volumes.volumes.sumOf { volume -> volume.chapters.size }
                                if (count > 0) chapterCountsByBook[value] = count
                                else chapterCountsByBook.remove(value)
                            }
                            _uiState.bookVolumes = it
                        }
                    }
                }
            }
    }
    private var chapterId = ""
    private var lastModeChapterId: String? = null

    init {
        viewModelScope.launch {
            settingState.isUsingFlipPageUserData.getFlowWithDefault(false).collect { flip ->
                val mode = if (flip) ReaderMode.Flip else ReaderMode.Scroll
                if (modeHost.select(mode, { bookId }, ::currentChapterIdForModeSwitch)) {
                    _uiState.contentUiState = modeHost.uiState
                }
            }
        }
    }

    fun prevChapter() = modeHost.loadPrevChapter()

    fun nextChapter() = modeHost.loadNextChapter()

    fun changeChapter(chapterId: String) {
        this.chapterId = chapterId
        lastModeChapterId = chapterId
        modeHost.changeChapter(chapterId)
    }

    private fun currentChapterIdForModeSwitch(): String {
        val requestedChapterId = modeHost.requestedChapterId
            ?.takeIf { it.isNotBlank() }
        if (requestedChapterId != null) {
            lastModeChapterId = requestedChapterId
            return requestedChapterId
        }
        val displayedChapterId = _uiState.contentUiState?.readingChapterId
            ?.takeIf { it.isNotBlank() }
        if (displayedChapterId != null) {
            lastModeChapterId = displayedChapterId
        }
        return displayedChapterId ?: lastModeChapterId ?: chapterId
    }

    private fun saveReadingProgress(chapterId: String, progress: Float) =
        readingRecords.saveProgress(chapterId, progress)

    fun updateTotalReadingTime(bookId: String, totalReadingTime: Int) =
        readingRecords.updateTotalReadingTime(bookId, totalReadingTime)

    fun accumulateReadingTime(bookId: String, seconds: Int) =
        readingRecords.accumulateReadingTime(bookId, seconds)

    override fun onCleared() {
        modeHost.close()
        bookVolumesJob?.cancel()
        super.onCleared()
    }
}
