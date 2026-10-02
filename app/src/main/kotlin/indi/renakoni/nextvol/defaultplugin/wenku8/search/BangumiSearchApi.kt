package indi.renakoni.nextvol.defaultplugin.wenku8.search

import indi.renakoni.nextvol.data.bangumi.BangumiApi
import indi.renakoni.nextvol.data.bangumi.BangumiRelatedSubject
import indi.renakoni.nextvol.data.bangumi.BangumiSearchPage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Public reads only. Account sync never uses the third-party mirror or its transport. */
@Singleton
class BangumiSearchApi internal constructor(private val official: BangumiApi, private val mirror: BangumiApi) {
    @Inject constructor(official: BangumiApi) : this(official, BangumiApi(
        OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build(), "https://api.bangumi.vip/".toHttpUrl()))

    private val gate = Semaphore(4)

    suspend fun search(keyword: String, offset: Int, tag: Boolean): BangumiSearchPage = read {
        if (tag) searchTag(keyword, offset) else search(keyword, offset)
    }

    suspend fun related(id: Int): List<BangumiRelatedSubject> = read { related(id) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T : Any> read(block: suspend BangumiApi.() -> T): T = coroutineScope {
        // An immediate failure starts the mirror immediately; a slow official read gets a head start.
        val failed = CompletableDeferred<Unit>()
        fun attempt(api: BangumiApi, backup: Boolean) = flow {
            if (backup) withTimeoutOrNull(750) { failed.await() }
            try {
                val result = gate.withPermit { withTimeoutOrNull(8_000) { api.block() } }
                if (result != null) { emit(result); return@flow }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
            }
            if (!backup) failed.complete(Unit)
        }
        // firstOrNull cancels the other HTTP call, including when the user cancels the query.
        merge(attempt(official, false), attempt(mirror, true)).firstOrNull()
            ?: throw IOException("Bangumi search unavailable")
    }
}
