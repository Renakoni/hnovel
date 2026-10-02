package indi.renakoni.nextvol.defaultplugin.wenku8.search

import indi.renakoni.nextvol.data.bangumi.BangumiRelatedSubject
import indi.renakoni.nextvol.data.bangumi.BangumiSubject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

internal data class SearchExpansion(
    val matches: List<Pair<Wenku8SearchEntry, Int>> = emptyList(),
    val names: List<String> = emptyList(), val hasMore: Boolean = false,
)

@Singleton
class BangumiSearchExpansion @Inject constructor(private val api: BangumiSearchApi) {
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun search(keyword: String, page: Int, catalog: List<Wenku8SearchEntry>): Flow<SearchExpansion> = channelFlow {
        val index = catalog.flatMap { entry -> entry.names.map { it to entry } }.groupBy({ it.first }, { it.second })
        val query = Wenku8SearchText.key(keyword)
        val relations = mutableMapOf<Int, List<BangumiRelatedSubject>>()
        val resolved = mutableSetOf<Int>()
        val fallback = linkedMapOf<Int, String>()
        var relationRequests = 0
        var hasMore = false
        fun searchPage(tag: Boolean) = flow {
            try {
                val result = api.search(keyword, (page - 1) * 10, tag)
                emit(tag to result)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
        data class Candidate(val id: Int, val names: List<String>, val depth: Int, val root: Int, val score: Int)
        withTimeoutOrNull(16_000) {
            merge(searchPage(false), searchPage(true)).collect { (tag, result) ->
                hasMore = hasMore || (page * 10 < result.total && result.data.isNotEmpty())
                val queue = ArrayDeque<Candidate>()
                val expanded = mutableSetOf<Int>()
                // Resolve single volumes before walking unrelated manga/series relationships.
                val ordered = result.data.sortedWith(compareByDescending<BangumiSubject> { it.isNovel }
                    .thenBy { it.series })
                suspend fun match(candidate: Candidate) {
                    val books = candidate.names.flatMap { index[Wenku8SearchText.key(it)].orEmpty() }.distinctBy { it.id }
                    if (books.isNotEmpty()) {
                        resolved += candidate.root
                        send(SearchExpansion(matches = books.map { it to candidate.score }))
                    }
                }
                for (subject in ordered) {
                    val names = (listOf(subject.nameCn, subject.name) + subject.values("别名")).filter(String::isNotBlank).distinct()
                    val exact = names.any { Wenku8SearchText.key(it) == query }
                    val candidate = Candidate(subject.id, names, 0, subject.id, if (exact) 5 else if (tag) 10 else 30)
                    match(candidate)
                    if (subject.isNovel && subject.series && subject.nameCn.isNotBlank() && (tag || exact))
                        fallback[subject.id] = subject.nameCn
                    if ((tag && subject.isNovel) || exact || tag && ordered.none { it.isNovel }) queue += candidate
                }
                while (queue.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val candidate = queue.removeFirst()
                    if (candidate.depth >= 2 || !expanded.add(candidate.id)) continue
                    var related = relations[candidate.id]
                    if (related == null) {
                        if (relationRequests >= 6) continue
                        relationRequests++
                        try { related = api.related(candidate.id).also { relations[candidate.id] = it } }
                        catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            continue
                        }
                    }
                    val children = related.filter { it.type == 1 && it.relation in setOf("系列", "前传", "续集", "书籍") }
                        .sortedBy { if (it.relation == "系列") 0 else if (it.relation == "续集") 1 else 2 }
                        .map { Candidate(it.id, listOf(it.nameCn, it.name).filter(String::isNotBlank),
                            candidate.depth + 1, candidate.root, candidate.score + 1) }
                    children.forEach { match(it) }
                    children.asReversed().forEach { queue.addFirst(it) }
                }
            }
        }
        send(SearchExpansion(names = fallback.filterKeys { it !in resolved }.values.distinct().take(2),
            hasMore = hasMore))
    }.flowOn(Dispatchers.IO)
}
