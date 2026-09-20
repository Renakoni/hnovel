package indi.renakoni.nextvol.data.bangumi

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import hnovel.network.StorageCipher
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import io.nightfish.lightnovelreader.api.book.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BangumiRepositoryTest {
    private lateinit var database: NextVolDatabase
    private lateinit var local: LocalBookDataSource
    private lateinit var accounts: BangumiAccountStore
    private lateinit var api: BangumiApi
    private lateinit var repository: BangumiRepository
    private val server = MockWebServer()
    private val book = BookIdentity.book("1213")
    @Volatile private var remote: BangumiCollection? = BangumiCollection(3, 0, 17, true)
    @Volatile private var nextFailure = 0
    @Volatile private var failReadAfterWrite = false
    @Volatile private var readGate: Pair<CountDownLatch, CountDownLatch>? = null
    private val writes = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val volumes get() = (1..2).map { number -> Volume(BookIdentity.volumeKey(book, "$number"), "第${number}卷",
        listOf(ChapterInformation(chapter(number), "正文"))) }
    private fun chapter(number: Int) = SourceChapterId(book, "$number").storageKey

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        local = LocalBookDataSource(database.bookInformationDao(), database.bookVolumesDao(), database.chapterContentDao(), database.userReadingDataDao())
        accounts = BangumiAccountStore(context, StorageCipher.Plain)
        accounts.disconnect()
        accounts.connect(BangumiUser(17, "test"), "test-only-token")
        local.updateBookInformation(BookInformation(book.storageKey, "Novel", "", Uri.EMPTY, "Author", "", emptyList(), "Publisher", WordCount(0), LocalDateTime.MIN, true))
        local.updateBookVolumes(BookVolumes(book.storageKey, volumes))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path == "/v0/me") return json("""{"id":17,"username":"test"}""")
                if (path == "/v0/subjects/10") return json("""{"id":10,"type":1,"name":"Novel","platform":"小说","series":true,"volumes":2}""")
                if (path == "/v0/subjects/10/subjects") return json("""[{"id":11,"type":1,"name":"Novel (1)","relation":"单行本"},{"id":12,"type":1,"name":"Novel (2)","relation":"单行本"}]""")
                if (path == "/v0/users/17/collections/10") {
                    readGate?.let { (started, release) -> started.countDown(); release.await(5, TimeUnit.SECONDS) }
                    if (nextFailure != 0) return MockResponse().setResponseCode(nextFailure.also { nextFailure = 0 }).setHeader("Retry-After", "60")
                    return remote?.let { json(bangumiJson.encodeToString(it)) } ?: MockResponse().setResponseCode(404)
                }
                if (path == "/v0/users/-/collections/10") {
                    if (request.getHeader("Content-Type") != "application/json") return MockResponse().setResponseCode(415)
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    writes += body
                    if (request.method == "POST") remote = BangumiCollection(3, private = body["private"]!!.jsonPrimitive.boolean)
                    else {
                        val value = remote ?: return MockResponse().setResponseCode(404)
                        remote = value.copy(type = body["type"]?.jsonPrimitive?.int ?: value.type,
                            volumes = body["vol_status"]?.jsonPrimitive?.int ?: value.volumes)
                        if (failReadAfterWrite && "vol_status" in body) {
                            failReadAfterWrite = false
                            nextFailure = 503
                        }
                    }
                    return MockResponse().setResponseCode(if (request.method == "POST") 202 else 204)
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        api = BangumiApi(OkHttpClient(), server.url("/"))
        repository = BangumiRepository(accounts, api, database)
    }

    @After fun close() = runBlocking { accounts.disconnect(); database.close(); server.shutdown() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun read(vararg numbers: Int) = local.updateUserReadingData(book.storageKey) {
        it.copy(maxChapterReadingProgressMap = numbers.associate { n -> chapter(n) to 1f })
    }
    private suspend fun bind() {
        val preview = repository.preview(book.storageKey, 10)
        repository.bind(preview, preview.mapping, emptySet(), true)
    }
    private suspend fun binding() = database.bangumiBindingDao().get(17, book.storageKey)!!.binding()

    @Test fun completedVolumesSyncOnceWithoutChangingChaptersOrReadingState() = runBlocking {
        read(1, 2); bind()
        assertFalse(repository.syncAll())
        assertEquals(BangumiCollection(3, 2, 17, true), remote)
        assertEquals(1, writes.size)
        assertEquals(setOf("vol_status"), writes.single().keys)
        assertFalse(repository.syncAll())
        assertEquals(1, writes.size)
        assertEquals(BangumiSyncStatus.SYNCED, binding().status)
        assertEquals(2, local.getUserReadingData(book.storageKey).maxChapterReadingProgressMap.size)
    }

    @Test fun missingCollectionIsCreatedPrivatelyAndThenUpdated() = runBlocking {
        remote = null
        read(1); bind(); repository.syncAll()
        assertEquals(2, writes.size)
        assertEquals(setOf("type", "private"), writes[0].keys)
        assertEquals(BangumiCollection(3, 1, 0, true), remote)
    }

    @Test fun aSingleBookSubjectCannotBeBoundToSeveralPublications() = runBlocking {
        val preview = repository.preview(book.storageKey, 10).let { it.copy(subject = it.subject.copy(series = false)) }
        try { repository.bind(preview, preview.mapping, emptySet(), true); fail() }
        catch (_: IllegalArgumentException) { }
        assertNull(database.bangumiBindingDao().get(17, book.storageKey))
        assertTrue(writes.isEmpty())
    }

    @Test fun alreadyCompletedCollectionIsOnlyResumedAfterBindingConfirmation() = runBlocking {
        remote = BangumiCollection(2, 0, 17)
        read(1)
        val preview = repository.preview(book.storageKey, 10)
        assertTrue(writes.isEmpty())
        repository.bind(preview, preview.mapping, emptySet(), true)
        repository.syncAll()
        assertEquals(setOf("type"), writes[0].keys)
        assertEquals(3, remote!!.type)
        assertEquals(17, remote!!.chapters)
    }

    @Test fun aHigherRemoteCountIsPreserved() = runBlocking {
        remote = BangumiCollection(3, 5)
        read(1); bind(); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(5, remote!!.volumes)
        assertEquals(BangumiSyncStatus.REMOTE_AHEAD, binding().status)
    }

    @Test fun unreadResetBeforeSendDropsThePendingTargetButNotAnAcknowledgedVolume() = runBlocking {
        bind(); repository.syncAll()
        read(1); assertTrue(repository.reconcile().isNotEmpty())
        read(); repository.syncAll()
        assertTrue(writes.isEmpty())
        read(1); repository.syncAll()
        read(); repository.syncAll()
        assertEquals(1, remote!!.volumes)
        assertEquals(1, writes.size)
    }

    @Test fun remoteRegressionAndStatusChangesPauseInsteadOfBeingOverwritten() = runBlocking {
        read(1); bind(); repository.syncAll()
        remote = remote!!.copy(volumes = 0)
        read(1, 2); repository.syncAll()
        assertEquals(BangumiSyncStatus.REMOTE_CHANGED, binding().status)
        assertEquals(1, writes.size)
        assertEquals(0, remote!!.volumes)
    }

    @Test fun manualWebsiteStatusIsPreservedAfterTheInitialLink() = runBlocking {
        bind(); repository.syncAll()
        remote = remote!!.copy(type = 2)
        read(1); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(BangumiSyncStatus.REMOTE_STATE, binding().status)
    }

    @Test fun addedChapterInvalidatesMappingBeforeAnyWrite() = runBlocking {
        read(1); bind()
        val changed = volumes.mapIndexed { index, volume -> if (index == 0) volume.copy(chapters = volume.chapters +
            ChapterInformation(SourceChapterId(book, "new").storageKey, "补章")) else volume }
        local.updateBookVolumes(BookVolumes(book.storageKey, changed))
        repository.syncAll()
        assertTrue(writes.isEmpty())
        assertEquals(BangumiSyncStatus.MAPPING_CHANGED, binding().status)
    }

    @Test fun freshWorkerRecoversProgressWithoutAnInMemoryReadingEvent() = runBlocking {
        bind(); repository.syncAll()
        read(1)
        val restoredAccounts = BangumiAccountStore(RuntimeEnvironment.getApplication(), StorageCipher.Plain)
        val restoredRepository = BangumiRepository(restoredAccounts, api, database)
        restoredRepository.syncAll()
        assertEquals(1, remote!!.volumes)
        restoredAccounts.disconnect()
    }

    @Test fun authenticationFailurePausesAndRateLimitRetainsBackoff() = runBlocking {
        read(1); bind()
        nextFailure = 429
        assertTrue(repository.syncAll())
        val count = server.requestCount
        assertTrue(repository.syncAll())
        assertEquals(count, server.requestCount)
        repository.retry(book.storageKey)
        nextFailure = 401
        assertFalse(repository.syncAll())
        assertEquals(BangumiSyncStatus.AUTH_REQUIRED, binding().status)
        assertTrue(writes.isEmpty())
    }

    @Test fun disconnectAndUnlinkPreventLaterQueuedWritesWithoutDeletingRemoteCollection() = runBlocking {
        read(1); bind(); repository.unlink(book.storageKey)
        repository.syncAll()
        assertTrue(writes.isEmpty())
        bind(); accounts.disconnect(); repository.syncAll()
        assertTrue(writes.isEmpty())
        assertNotNull(remote)
    }

    @Test fun explicitRetryChecksRemoteEvenWhenLocalCountDidNotChange() = runBlocking {
        bind(); repository.syncAll()
        repository.retry(book.storageKey)
        assertTrue(repository.reconcile().isNotEmpty())
        remote = remote!!.copy(type = 4)
        repository.syncAll()
        assertEquals(BangumiSyncStatus.REMOTE_STATE, binding().status)
    }

    @Test fun acceptedWriteSurvivesFailedReadbackUnreadResetAndWorkerRecreation() = runBlocking {
        bind(); repository.syncAll(); read(1)
        failReadAfterWrite = true
        assertTrue(repository.syncAll())
        assertEquals(1, remote!!.volumes)
        assertEquals(setOf("subject:11"), binding().pendingEditions)
        read()
        assertTrue(repository.reconcile().isNotEmpty())
        repository.retry(book.storageKey)
        val fresh = BangumiRepository(accounts, api, database)
        assertFalse(fresh.syncAll())
        assertEquals(setOf("subject:11"), binding().acknowledged)
        assertTrue(binding().pendingEditions.isEmpty())
        read(2); fresh.syncAll()
        assertEquals(2, remote!!.volumes)
        assertEquals(2, writes.size)
    }

    @Test fun unlinkCancelsTheInflightReadBeforeItCanPatch() = runBlocking {
        bind(); repository.syncAll(); read(1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        readGate = started to release
        try {
            val syncing = async(Dispatchers.IO) { repository.syncAll() }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(3000) { repository.unlink(book.storageKey) }
            release.countDown()
            syncing.await()
            assertNull(database.bangumiBindingDao().get(17, book.storageKey))
            assertTrue(writes.isEmpty())
        } finally { readGate = null; release.countDown() }
    }

    @Test fun reconnectAfterDisconnectRequiresReviewAndAnotherAccountCannotUseOldBindings() = runBlocking {
        bind(); repository.syncAll(); read(1)
        accounts.connect(BangumiUser(18, "other"), "another-test-token")
        repository.syncAll()
        assertTrue(writes.isEmpty())
        accounts.disconnect()
        repository.connect("replacement-test-token")
        assertEquals(BangumiSyncStatus.REMOTE_CHANGED, binding().status)
        repository.syncAll()
        assertTrue(writes.isEmpty())
    }

    @Test fun roomCommitAutomaticallySchedulesWorkAndFailureDoesNotEnqueueAFeedbackLoop() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == BangumiSyncWork::class.java.name) BangumiSyncWork(appContext, workerParameters, repository) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            bind()
            BangumiSyncScheduler(repository, database, manager).start(scope)
            val first = withTimeout(10_000) { manager.getWorkInfosForUniqueWorkFlow("bangumi-progress").first { it.isNotEmpty() }.single() }
            driver.setAllConstraintsMet(first.id)
            withTimeout(10_000) { manager.getWorkInfoByIdFlow(first.id).first { it?.state?.isFinished == true } }
            assertTrue(writes.isEmpty())
            nextFailure = 429
            read(1)
            val second = withTimeout(10_000) { manager.getWorkInfosForUniqueWorkFlow("bangumi-progress").first { list -> list.any { it.id != first.id } }.first { it.id != first.id } }
            driver.setAllConstraintsMet(second.id)
            withTimeout(10_000) { manager.getWorkInfoByIdFlow(second.id).first { it?.runAttemptCount == 1 && it.state == WorkInfo.State.ENQUEUED } }
            val count = server.requestCount
            // Cover two sampling ticks after the worker's own database updates.
            delay(1700)
            assertEquals(2, manager.getWorkInfosForUniqueWork("bangumi-progress").get().size)
            assertEquals(count, server.requestCount)
            assertEquals(BangumiSyncStatus.OFFLINE, binding().status)
            assertTrue(writes.isEmpty())
            accounts.disconnect()
            withTimeout(10_000) {
                manager.getWorkInfosForUniqueWorkFlow("bangumi-progress").first { list -> list.all { it.state.isFinished } }
                manager.getWorkInfosForUniqueWorkFlow("bangumi-recovery").first { list -> list.all { it.state.isFinished } }
            }
            assertTrue(writes.isEmpty())
        } finally {
            scope.cancel()
            manager.cancelAllWork().await()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
