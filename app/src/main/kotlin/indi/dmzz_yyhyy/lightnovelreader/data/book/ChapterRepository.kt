package indi.dmzz_yyhyy.lightnovelreader.data.book

import android.util.Log
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterRepository @Inject constructor(
    private val sourceRegistry: WebSourceRegistry,
    private val localBookDataSource: LocalBookDataSource,
    private val textProcessingRepository: TextProcessingRepository,
) : ChapterSource {
    companion object {
        private const val TAG = "BookRepository"
    }


    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookVolumes, WebRequestError>> = flow {
        val book = BookIdentity.book(id)
        val local = localBookDataSource.getBookVolumes(book.storageKey)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        sourceRegistry.request(book) { it.getBookVolumes(book.remoteId, priority) }.map(book::bind)
            .onOk { remote ->
                localBookDataSource.updateBookVolumes(remote)
            }.onErr {
                Log.e(TAG, "Source request failed for ${book.fileKey}: ${it.kind}")
            }
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processBookVolumes { it }
        }
    }

    override fun getChapterContentFlow(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ): Flow<Result<ChapterContent, WebRequestError>> = flow {
        val chapter = BookIdentity.chapter(chapterId, BookIdentity.book(bookId))
        val local = localBookDataSource.getChapterContent(chapter.storageKey)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        sourceRegistry.request(chapter.book) { it.getChapterContent(chapter.remoteId, chapter.book.remoteId, priority) }.map(chapter::bind)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(remote)
            }.onErr {
                Log.e(TAG, "Source request failed for ${chapter.book.fileKey}: ${it.kind}")
            }
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processChapterContent(BookIdentity.bookKey(bookId)) { it }
        }
    }

    override suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ) {
        val chapter = BookIdentity.chapter(chapterId, BookIdentity.book(bookId))
        sourceRegistry.request(chapter.book) { it.getChapterContent(chapter.remoteId, chapter.book.remoteId, priority) }.map(chapter::bind)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(remote)
            }.onErr {
                Log.e(TAG, "Source request failed for ${chapter.book.fileKey}: ${it.kind}")
            }
    }
}
