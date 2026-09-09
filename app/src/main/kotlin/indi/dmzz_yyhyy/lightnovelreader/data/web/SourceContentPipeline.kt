package indi.dmzz_yyhyy.lightnovelreader.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier

enum class PipelineStage { Search, Information, Directory, Content }
sealed interface PipelineFailure {
    data object Cancelled : PipelineFailure
    data object LoginRequired : PipelineFailure
    data object StaleContext : PipelineFailure
    data class Failed(val message: String) : PipelineFailure
}
data class PipelineContext(val source: Identifier, val revision: String, val accountGeneration: Long)
data class PipelineBook(val id: String, val title: String, val author: String = "", val coverUrl: String = "")
data class PipelineChapter(val id: String, val bookId: String, val title: String, val order: Int)
data class PipelineContent(val chapterId: String, val body: String, val imageUrls: List<String> = emptyList())

interface SourcePipelineExecutor {
    suspend fun search(context: PipelineContext, keyword: String): PipelineResult<List<PipelineBook>>
    suspend fun information(context: PipelineContext, bookId: String): PipelineResult<PipelineBook>
    suspend fun directory(context: PipelineContext, bookId: String, cursor: String?): PipelineResult<Page<PipelineChapter>>
    suspend fun content(context: PipelineContext, chapter: PipelineChapter): PipelineResult<PipelineContent>
}

data class Page<T>(val items: List<T>, val nextCursor: String?)
sealed interface PipelineResult<out T> { data class Success<T>(val value: T): PipelineResult<T>; data class Failure(val error: PipelineFailure): PipelineResult<Nothing> }

fun resolveSourceLink(base: String, link: String): String? = try {
    java.net.URI(base).resolve(link).takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
} catch (_: Exception) { null }

/** Keeps source/session identity attached to every stage and rejects stale results. */
class SourceContentPipeline(private val executor: SourcePipelineExecutor,
    private val contextIsCurrent: (PipelineContext) -> Boolean = { true }) {
    private fun current(context: PipelineContext) = contextIsCurrent(context)
    suspend fun search(context: PipelineContext, keyword: String): PipelineResult<List<PipelineBook>> {
        if (!current(context)) return PipelineResult.Failure(PipelineFailure.StaleContext)
        return executor.search(context, keyword).let { if (current(context)) it else PipelineResult.Failure(PipelineFailure.StaleContext) }
    }
    suspend fun information(context: PipelineContext, bookId: String): PipelineResult<PipelineBook> {
        if (!current(context)) return PipelineResult.Failure(PipelineFailure.StaleContext)
        return executor.information(context, bookId).let { if (current(context)) it else PipelineResult.Failure(PipelineFailure.StaleContext) }
    }
    suspend fun directory(context: PipelineContext, bookId: String): PipelineResult<List<PipelineChapter>> {
        val all = mutableListOf<PipelineChapter>(); val seen = mutableSetOf<String>(); val cursors = mutableSetOf<String?>(); var cursor: String? = null
        while (true) {
            if (!current(context)) return PipelineResult.Failure(PipelineFailure.StaleContext)
            if (!cursors.add(cursor)) return PipelineResult.Failure(PipelineFailure.Failed("repeated directory cursor"))
            when (val page = executor.directory(context, bookId, cursor)) {
                is PipelineResult.Failure -> return page
                is PipelineResult.Success -> {
                    val ids = page.value.items.map { it.id }
                    if (ids.size != ids.toSet().size || ids.any { it in seen })
                        return PipelineResult.Failure(PipelineFailure.Failed("repeated directory page"))
                    seen.addAll(ids)
                    all += page.value.items
                    if (!current(context)) return PipelineResult.Failure(PipelineFailure.StaleContext)
                    cursor = page.value.nextCursor ?: return PipelineResult.Success(all.sortedBy { it.order })
                }
            }
        }
    }
    suspend fun content(context: PipelineContext, chapter: PipelineChapter): PipelineResult<PipelineContent> {
        if (!current(context)) return PipelineResult.Failure(PipelineFailure.StaleContext)
        return executor.content(context, chapter).let { if (current(context)) it else PipelineResult.Failure(PipelineFailure.StaleContext) }
    }
}
