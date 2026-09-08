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
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
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
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localBookDataSource: LocalBookDataSource,
    private val textProcessingRepository: TextProcessingRepository,
) : ChapterSource {
    companion object {
        private const val TAG = "BookRepository"
    }

    private val webBookDataSource get() = webBookDataSourceProvider.value

    override fun getBookVolumesFlow(
        id: String,
        priority: WebDataSourcePriority
    ): Flow<Result<BookVolumes, WebRequestError>> = flow {
        val local = localBookDataSource.getBookVolumes(id)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        webBookDataSource.getBookVolumes(id, priority)
            .onOk { remote ->
                localBookDataSource.updateBookVolumes(remote)
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
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
        val local = localBookDataSource.getChapterContent(chapterId)
        local?.also {
            emit(Ok(it))
            if (BuildConfig.BENCHMARK) return@flow
        }
        webBookDataSource.getChapterContent(chapterId, bookId, priority)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(remote)
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
            .also {
                if (it.isOk || local == null) emit(it)
            }
    }.map { result ->
        result.map {
            textProcessingRepository.processChapterContent(bookId) { it }
        }
    }

    override suspend fun preloadChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ) {
        webBookDataSource.getChapterContent(chapterId, bookId, priority)
            .onOk { remote ->
                localBookDataSource.updateChapterContent(remote)
            }.onErr {
                Log.e(TAG, "Failed to request web data (title=${it.title}, message=${it.message})")
                it.throwable?.printStackTrace()
            }
    }
}
