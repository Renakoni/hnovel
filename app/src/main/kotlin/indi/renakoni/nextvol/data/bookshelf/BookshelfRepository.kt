package indi.renakoni.nextvol.data.bookshelf

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookAliasStore
import indi.renakoni.nextvol.data.download.BookDownloadStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import indi.renakoni.nextvol.data.local.room.converter.ListConverter
import indi.renakoni.nextvol.data.local.room.dao.BookshelfDao
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.work.CacheBookWork
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfBookMetadata
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfRepositoryApi
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookshelfRepository @Inject constructor(
    private val bookshelfDao: BookshelfDao, private val workManager: WorkManager,
    private val sourceRegistry: indi.renakoni.nextvol.data.web.WebSourceRegistry,
    private val downloads: BookDownloadStore,
    private val aliases: BookAliasStore,
) : BookshelfRepositoryApi {
    override suspend fun getAllBookshelfIds(): List<Int> = bookshelfDao.getAllBookshelfIds()

    override suspend fun getAllBookshelves(): List<Bookshelf> =
        bookshelfDao.getAllBookshelves().map { bookshelfEntity ->
            Bookshelf(
                id = bookshelfEntity.id,
                name = bookshelfEntity.name,
                sortType = BookshelfSortType.map(bookshelfEntity.sortType),
                sortReversed = bookshelfEntity.sortReversed,
                autoCache = bookshelfEntity.autoCache,
                systemUpdateReminder = bookshelfEntity.systemUpdateReminder,
                allBookIds = bookshelfEntity.allBookIds,
                pinnedBookIds = bookshelfEntity.pinnedBookIds,
                updatedBookIds = bookshelfEntity.updatedBookIds
            )
        }

    override fun getAllBookshelvesFlow(): Flow<List<Bookshelf>> =
        bookshelfDao.getAllBookshelvesFlow().map { bookshelfEntities ->
            bookshelfEntities.map { bookshelfEntity ->
                Bookshelf(
                    id = bookshelfEntity.id,
                    name = bookshelfEntity.name,
                    sortType = BookshelfSortType.map(bookshelfEntity.sortType),
                    sortReversed = bookshelfEntity.sortReversed,
                    autoCache = bookshelfEntity.autoCache,
                    systemUpdateReminder = bookshelfEntity.systemUpdateReminder,
                    allBookIds = bookshelfEntity.allBookIds,
                    pinnedBookIds = bookshelfEntity.pinnedBookIds,
                    updatedBookIds = bookshelfEntity.updatedBookIds
                )
            }
        }

    override suspend fun getBookshelf(id: Int): Bookshelf? {
        val bookshelfEntity = bookshelfDao.getBookshelf(id) ?: return null
        return Bookshelf(
            id = id,
            name = bookshelfEntity.name,
            sortType = BookshelfSortType.map(bookshelfEntity.sortType),
            sortReversed = bookshelfEntity.sortReversed,
            autoCache = bookshelfEntity.autoCache,
            systemUpdateReminder = bookshelfEntity.systemUpdateReminder,
            allBookIds = bookshelfEntity.allBookIds,
            pinnedBookIds = bookshelfEntity.pinnedBookIds,
            updatedBookIds = bookshelfEntity.updatedBookIds
        )
    }

    override fun getBookshelfFlow(id: Int): Flow<Bookshelf?> =
        bookshelfDao.getBookShelfFlow(id).map { bookshelfEntity ->
                bookshelfEntity ?: return@map null
                Bookshelf(
                    id = id,
                    name = bookshelfEntity.name,
                    sortType = BookshelfSortType.map(bookshelfEntity.sortType),
                    sortReversed = bookshelfEntity.sortReversed,
                    autoCache = bookshelfEntity.autoCache,
                    systemUpdateReminder = bookshelfEntity.systemUpdateReminder,
                    allBookIds = bookshelfEntity.allBookIds,
                    pinnedBookIds = bookshelfEntity.pinnedBookIds,
                    updatedBookIds = bookshelfEntity.updatedBookIds
                )
            }

    private suspend fun key(id: String) = aliases.resolve(BookIdentity.book(id)).storageKey
    private suspend fun keys(ids: List<String>) = ids.map { key(it) }.distinct()

    override suspend fun addBookshelf(bookshelf: Bookshelf) = aliases.transaction {
        bookshelfDao.insertBookshelf(
            BookshelfEntity(
                bookshelf.id,
                bookshelf.name,
                bookshelf.sortType.key,
                bookshelf.sortReversed,
                bookshelf.autoCache,
                bookshelf.systemUpdateReminder,
                keys(bookshelf.allBookIds),
                keys(bookshelf.pinnedBookIds),
                keys(bookshelf.updatedBookIds),
            )
        )
    }

    override suspend fun deleteBookshelf(bookshelfId: Int) = aliases.transaction {
        bookshelfDao.getBookshelf(bookshelfId)?.let { bookshelf ->
            bookshelf.allBookIds.forEach { bookId ->
                clearBookshelfIdFromBookshelfBookMetadata(bookshelfId, bookId)
            }
        }
        bookshelfDao.deleteBookshelf(bookshelfId)
    }

    override suspend fun addBookIntoBookShelf(bookshelfId: Int, bookInformation: BookInformation) = aliases.transaction {
        val bookId = key(bookInformation.id)
        val bookshelf = bookshelfDao.getBookshelf(bookshelfId) ?: return@transaction
        bookshelfDao.addBookshelfMetadata(
            id = bookId,
            lastUpdate = bookInformation.lastUpdated,
            bookshelfIds = listOf(bookshelfId)
        )
        // Metadata-only sources can be bookmarked, but an automatic cache would always fail.
        val sourceId = BookIdentity.book(bookId).sourceId
        val canCache = sourceRegistry.sources.value.any { it.metadata.id == sourceId && it.metadata.supportsReading }
        if (canCache && bookshelf.autoCache && bookshelf.allBookIds.contains(bookId)) {
            val generation = downloads.generation()
            val workRequest = OneTimeWorkRequestBuilder<CacheBookWork>()
                .addTag(CacheBookWork.generationTag(generation)).setInputData(
                    workDataOf(
                        "bookId" to bookId,
                        "downloadGeneration" to generation,
                    )
                ).build()
            workManager.enqueueUniqueWork(
                CacheBookWork.ofId(bookId), ExistingWorkPolicy.KEEP, workRequest
            )
        }
        (bookshelf.allBookIds + listOf(bookId)).let {
            bookshelfDao.insertBookshelf(
                bookshelf.copy(
                    allBookIds = it.distinct(),
                )
            )
        }
    }

    override suspend fun addUpdatedBooksIntoBookShelf(bookShelfId: Int, bookId: String) = aliases.transaction {
        val key = key(bookId)
        val bookshelf = bookshelfDao.getBookshelf(bookShelfId) ?: return@transaction
        (bookshelf.updatedBookIds + listOf(key)).let {
            bookshelfDao.insertBookshelf(
                bookshelf.copy(
                    updatedBookIds = it.distinct(),
                )
            )
        }
    }

    override suspend fun updateBookshelf(bookshelfId: Int, updater: (Bookshelf) -> Bookshelf): Unit = aliases.transaction {
        this.getBookshelf(bookshelfId)?.let { oldBookshelf ->
            updater(oldBookshelf).let { newBookshelf ->
                bookshelfDao.insertBookshelf(
                    BookshelfEntity(
                        bookshelfId,
                        newBookshelf.name,
                        newBookshelf.sortType.key,
                        newBookshelf.sortReversed,
                        newBookshelf.autoCache,
                        newBookshelf.systemUpdateReminder,
                        keys(newBookshelf.allBookIds),
                        keys(newBookshelf.pinnedBookIds),
                        keys(newBookshelf.updatedBookIds),
                    )
                )
            }
        }
    }

    override suspend fun getAllBookshelfBooksMetadata(): List<BookshelfBookMetadata> =
        bookshelfDao.getAllBookshelfBookEntities().map {
                BookshelfBookMetadata(
                    it.id, it.lastUpdate, it.bookShelfIds
                )
            }

    override fun getAllBookshelfBookIdsFlow(): Flow<List<String>> =
        bookshelfDao.getAllBookshelfBookIdsFlow()

    override suspend fun getBookshelfBookMetadata(id: String): BookshelfBookMetadata? =
        aliases.withResolved(BookIdentity.book(id)) { bookshelfDao.getBookshelfBookMetadata(it.storageKey) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getBookshelfBookMetadataFlow(id: String): Flow<BookshelfBookMetadata?> =
        aliases.observe(BookIdentity.book(id)).flatMapLatest { canonical ->
        bookshelfDao.getBookshelfBookMetadataEntityFlow(canonical.storageKey).map {
            it ?: return@map null
            BookshelfBookMetadata(
                it.id, it.lastUpdate, it.bookShelfIds
            )
        }
        }

    private suspend fun clearBookshelfIdFromBookshelfBookMetadata(
        bookshelfId: Int,
        bookId: String
    ) {
        bookshelfDao.getBookshelfBookMetadata(bookId)?.let { bookshelfBookMetadata ->
            bookshelfBookMetadata.bookShelfIds.toMutableList()
                .apply { removeAll { bookshelfId == it } }.let { bookshelfIds ->
                    if (bookshelfIds.isEmpty()) bookshelfDao.deleteBookshelfBookMetadata(bookId)
                    else bookshelfDao.insertBookshelfBookMetadata(
                        bookId,
                        bookshelfBookMetadata.lastUpdate,
                        ListConverter.intListToString(bookshelfIds)
                    )
                }
        }
    }

    override suspend fun deleteBookFromBookshelf(bookshelfId: Int, bookId: String) = aliases.transaction {
        val key = key(bookId)
        clearBookshelfIdFromBookshelfBookMetadata(bookshelfId, key)
        updateBookshelf(bookshelfId) { oldBookshelf ->
            oldBookshelf.copy(
                allBookIds = oldBookshelf.allBookIds.toMutableList()
                .apply { removeAll { it == key } },
                pinnedBookIds = oldBookshelf.pinnedBookIds.toMutableList()
                    .apply { removeAll { it == key } },
                updatedBookIds = oldBookshelf.updatedBookIds.toMutableList()
                    .apply { removeAll { it == key } })
        }
    }

    override suspend fun deleteBookFromBookshelfUpdatedBookIds(bookshelfId: Int, bookId: String) = aliases.transaction {
        val key = key(bookId)
        updateBookshelf(bookshelfId) { oldBookshelf ->
            oldBookshelf.copy(
                updatedBookIds = oldBookshelf.updatedBookIds.toMutableList()
                    .apply { removeAll { it == key } })
        }
    }

    override suspend fun updateBookshelfBookMetadataLastUpdateTime(
        bookId: String,
        time: LocalDateTime
    ) = aliases.transaction {
        val key = key(bookId)
        bookshelfDao.insertBookshelfBookMetadata(
            key,
            time,
            ListConverter.intListToString(
                bookshelfDao.getBookshelfBookMetadata(key)?.bookShelfIds ?: emptyList()
            )
        )
    }

    override suspend fun clear() = bookshelfDao.clear()
}
