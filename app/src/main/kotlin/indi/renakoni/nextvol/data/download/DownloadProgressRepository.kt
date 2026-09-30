package indi.renakoni.nextvol.data.download

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import io.nightfish.lightnovelreader.api.userdata.UserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadProgressRepository @Inject constructor(
    userDataDao: UserDataDao,
    val bookRepository: BookRepository,
    private val downloads: BookDownloadStore,
) {
    class DownItemListUserData (
        override val path: String,
        private val userDataDao: UserDataDao,
        private val bookRepository: BookRepository
    ) : UserData<List<DownloadItem>>(path) {
        override suspend fun set(value: List<DownloadItem>) {
            userDataDao.insert(path, group, "CompletedDownloadItemList", value.joinToString {
                "${it.type.name}|${it.bookId}"
            })
        }

        override suspend fun get(): List<DownloadItem>? {
            return userDataDao.get(path)?.split(",")?.mapNotNull {
                val values = it.split("|")
                try {
                    return@mapNotNull MutableDownloadItem(
                        DownloadType.valueOf(values[0].trim()),
                        values[1],
                        bookRepository.getBookInformationFlow(values[1])
                    ).apply { progress = 1f }
                } catch (e: Exception) {
                    Log.e("CompletedDownloadItemList", "Invalid completed download record")
                }
                return@mapNotNull null
            }
        }

        override fun getFlow(): Flow<List<DownloadItem>?> {
            return userDataDao.getFlow(path).map { value ->
                value?.split(",")?.map {
                    val values = it.split("|")
                    MutableDownloadItem(
                        DownloadType.valueOf(values[0].trim()),
                        values[1],
                        bookRepository.getBookInformationFlow(values[1])
                    ).apply { progress = 1f }
                }
            }
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val completedBookListUserData = DownItemListUserData(UserDataPath.CompletedDownloadBookList.path, userDataDao, bookRepository)
    private val _downloadItemList = mutableStateListOf<DownloadItem>()
    val downloadItemIdList: List<DownloadItem> get() = _downloadItemList.toList()
    private val cacheObservers = mutableMapOf<String, Job>()

    init {
        coroutineScope.launch {
            val completedBookList = completedBookListUserData.getOrDefault(emptyList())
            val saved = completedBookList.filter { it.type != DownloadType.CACHE }
            _downloadItemList.addAll(saved.filterNot { it in _downloadItemList })
            downloads.prepare()
            downloads.observeEntries().map { owners -> owners.filter { !it.taskHidden && it.taskStatus != DownloadTaskStatus.None.name }
                .map { it.bookId }.toSet() }
                .distinctUntilChanged().collect { ids ->
                    _downloadItemList.removeIf { it.type == DownloadType.CACHE && it.bookId !in ids }
                    cacheObservers.keys.filterNot { it in ids }.forEach { cacheObservers.remove(it)?.cancel() }
                    for (id in ids.filterNot { it in cacheObservers }) {
                        val item = MutableDownloadItem(DownloadType.CACHE, id, bookRepository.downloadInformationFlow(id))
                        _downloadItemList.add(item)
                        cacheObservers[id] = coroutineScope.launch {
                            bookRepository.downloadStatusFlow(id).distinctUntilChanged().collect { status ->
                                item.progress = when {
                                    status.task.active -> (status.content.savedChapters.toFloat() /
                                        status.content.totalChapters.coerceAtLeast(1)).coerceIn(0f, 0.99f)
                                    status.displayPhase == BookDownloadPhase.Complete && !status.task.canResume -> 1f
                                    else -> -1f
                                }
                                item.status = status
                            }
                        }
                    }
                }
        }
    }

    /** Finish background database work before the owning host closes its database. */
    suspend fun close() {
        coroutineScope.coroutineContext.job.cancelAndJoin()
    }

    fun addExportItem(downloadItem: DownloadItem) {
        // Cache items are rebuilt from persistent task facts, not a worker-owned object.
        if (downloadItem.type == DownloadType.CACHE) return
        if (_downloadItemList.contains(downloadItem))
            _downloadItemList.removeIf { it == downloadItem }
        _downloadItemList.add(downloadItem)
        coroutineScope.launch {
            snapshotFlow{ downloadItem.progress }.collect { progress ->
                if (progress >= 1f) {
                    completedBookListUserData.update(
                        updater = { downloadItems ->
                            val list = downloadItems.toMutableList()
                            if (list.contains(downloadItem))
                                list.removeIf { it == downloadItem }
                            list + downloadItem
                        },
                        default = emptyList()
                    )
                    return@collect
                }
            }
        }
    }

    fun removeExportItem(downloadItem: DownloadItem) {
        _downloadItemList.remove(downloadItem)
    }

    fun clearCachedItems(bookIds: Set<String>? = null) {
        _downloadItemList.removeIf { it.type == DownloadType.CACHE && (bookIds == null || it.bookId in bookIds) }
    }

    fun clearCompleted() {
        val cached = _downloadItemList.filter { it.type == DownloadType.CACHE && it.progress >= 1f }.map { it.bookId }
        // Persistent state decides whether a cache task can be hidden; the UI may lag a new retry.
        _downloadItemList.removeIf { it.type != DownloadType.CACHE && it.progress >= 1f }
        coroutineScope.launch {
            cached.forEach { downloads.dismissTask(indi.renakoni.nextvol.data.book.BookIdentity.book(it), cancel = false) }
            completedBookListUserData.set(emptyList())
        }
    }
}
