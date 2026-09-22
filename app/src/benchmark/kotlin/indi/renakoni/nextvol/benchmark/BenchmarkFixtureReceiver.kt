package indi.renakoni.nextvol.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookInformationEntity
import indi.renakoni.nextvol.data.local.room.entity.BookRecordEntity
import indi.renakoni.nextvol.data.local.room.entity.BookshelfBookMetadataEntity
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.local.room.entity.ChapterContentEntity
import indi.renakoni.nextvol.data.local.room.entity.ChapterInformationEntity
import indi.renakoni.nextvol.data.local.room.entity.DailyCountEntity
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity
import indi.renakoni.nextvol.data.local.room.entity.VolumeEntity
import indi.renakoni.nextvol.data.statistics.Count
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.network.NetworkGrant
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Seeds deterministic, local-only UI data into the benchmark build.
 *
 * This receiver is compiled only into the `benchmark` variant and is never
 * present in debug, snapshot, or release artifacts.
 */
@AndroidEntryPoint
class BenchmarkFixtureReceiver : BroadcastReceiver() {
    @Inject lateinit var sources: ImportedRuleSources
    @Inject lateinit var speechSources: indi.renakoni.nextvol.tts.HttpSpeechRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val result = when (intent.action) {
                    ACTION_SEED -> {
                        runBlocking {
                            seed(NextVolDatabase.getInstance(context))
                        }
                        "seed=SUCCEEDED"
                    }
                    ACTION_SEED_SOURCE -> {
                        runBlocking { seedSource() }
                        "source=SUCCEEDED"
                    }
                    ACTION_SPEECH_SOURCE -> {
                        runBlocking { speechSources.save(listOf(speechFixture())) }
                        "speech-source=SUCCEEDED"
                    }
                    ACTION_SPEECH_ENGINE -> {
                        val settings = buildJsonObject { put("engine", intent.getStringExtra("engine").orEmpty()) }
                        runBlocking {
                            NextVolDatabase.getInstance(context).userDataDao().insert(
                                UserDataEntity("tts.settings", "", "String", settings.toString()))
                        }
                        "speech=SUCCEEDED"
                    }
                    else -> "unsupported-action=${intent.action}"
                }
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = result
            } catch (throwable: Throwable) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData =
                    "exception=${throwable::class.java.simpleName}:${throwable.message}"
            } finally {
                pending.finish()
            }
        }.start()
    }

    private suspend fun seedSource() {
        val raw = buildJsonObject {
            put("bookSourceUrl", "https://runtime.invalid/")
            put("bookSourceName", "Runtime fixture")
            put("bookSourceType", 0)
            put("enabledExplore", true)
            put("jsLib", "function sharedLabel(){return 'Shared';}")
            put("exploreUrl", """
                @js:
                function row(name, read) {
                    let value;
                    try { value = read(); } catch (error) { value = 'ERROR ' + error; }
                    return {title: name + ': ' + value, url: '/books'};
                }
                JSON.stringify([
                    row('JavaScript', () => { const count = 6; return count * 7; }),
                    row('RegExp', () => /chapter (\d+)/.exec('chapter 35')[1]),
                    row('Typed arrays', () => {
                        const types = [Int8Array, Uint8Array, Uint8ClampedArray, Int16Array,
                            Uint16Array, Int32Array, Uint32Array, Float32Array, Float64Array];
                        const view = new DataView(new ArrayBuffer(4));
                        view.setInt32(0, 42);
                        return types.every(type => new type([42])[0] === view.getInt32(0));
                    }),
                    row('Continuation', () => typeof Continuation),
                    row('Errors', () => { try { throw new Error('controlled'); } catch (error) { return error.message; } }),
                    row('Library', () => sharedLabel()),
                    row('Bridge', () => String(source.getLoginHeader()) + '/' + java.base64Decode('aGVsbG8=')),
                    row('DOM', () => org.jsoup.Jsoup.parse('<h1>Chapter</h1>').select('h1').first().text()),
                    row('Isolation', () => {
                        // Rhino represents unlisted names as packages. Check the denied call,
                        // rather than treating a package placeholder as an exposed Java class.
                        let runtime = 'exposed';
                        try { Packages.java.lang.Runtime.getRuntime(); } catch (error) { runtime = 'blocked'; }
                        return runtime + '/' + typeof java.getClass;
                    })
                ])
            """.trimIndent())
        }
        val preview = sources.importer.preview(raw.toString())
        check(preview.issues.isEmpty())
        check(sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error == null)
        val definition = sources.definitions.list().single { it.importKey == "https://runtime.invalid/" }
        sources.activate(definition.reference(), listOf(NetworkGrant("https://runtime.invalid/")))
    }

    private suspend fun seed(database: NextVolDatabase) {
        val now = LocalDateTime.now()
        val book = BookInformationEntity(
            id = BOOK_ID,
            title = "Benchmark Sample Novel",
            subtitle = "A deterministic local test fixture",
            coverUri = Uri.EMPTY,
            author = "Benchmark Author",
            description = "Local content used to exercise every book and reader workflow without network access.",
            tags = listOf("Benchmark", "Local", "Automation"),
            publishingHouse = "Benchmark Press",
            wordCount = WordCount(12_345),
            lastUpdated = now.minusDays(1),
            isComplete = false,
        )
        database.bookInformationDao().insert(book)

        database.bookVolumesDao().insertVolume(
            VolumeEntity(
                bookId = BOOK_ID,
                volumeId = VOLUME_ID,
                volumeTitle = "Benchmark Volume",
                chapterIds = listOf(CHAPTER_ONE_ID),
                index = 0,
            )
        )
        database.bookVolumesDao().insertVolume(
            VolumeEntity(
                bookId = BOOK_ID,
                volumeId = SECOND_VOLUME_ID,
                volumeTitle = "Benchmark Bonus Volume",
                chapterIds = listOf(CHAPTER_TWO_ID),
                index = 1,
            )
        )
        database.bookVolumesDao().insertChapterInformationEntities(
            ChapterInformationEntity(CHAPTER_ONE_ID, "Benchmark Chapter One"),
            ChapterInformationEntity(CHAPTER_TWO_ID, "Benchmark Chapter Two"),
        )

        val firstContent = ContentBuilder()
            .simpleText(LONG_TEXT)
            .build()
        val secondContent = ContentBuilder()
            .simpleText("Benchmark chapter two.\n\n$LONG_TEXT")
            .build()
        database.chapterContentDao().update(
            ChapterContentEntity(
                id = CHAPTER_ONE_ID,
                title = "Benchmark Chapter One",
                content = firstContent,
                prevChapter = "",
                nextChapter = CHAPTER_TWO_ID,
            )
        )
        database.chapterContentDao().update(
            ChapterContentEntity(
                id = CHAPTER_TWO_ID,
                title = "Benchmark Chapter Two",
                content = secondContent,
                prevChapter = CHAPTER_ONE_ID,
                nextChapter = "",
            )
        )

        database.userReadingDataDao().insert(
            UserReadingDataEntity(
                id = BOOK_ID,
                lastReadTime = now,
                totalReadTime = 3_600,
                readingProgress = 0.25f,
                lastReadChapterId = CHAPTER_ONE_ID,
                lastReadChapterTitle = "Benchmark Chapter One",
                currentChapterReadingProgressMap = mapOf(CHAPTER_ONE_ID to 0.25f),
                maxChapterReadingProgressMap = mapOf(CHAPTER_ONE_ID to 0.5f),
            )
        )
        database.userDataDao().insert(
            UserDataEntity(
                path = "reading_books",
                group = "",
                type = "StringList",
                value = BOOK_ID,
            )
        )
        database.userDataDao().insert(
            UserDataEntity(
                path = "bookshelf_order",
                group = "",
                type = "StringList",
                value = BOOKSHELF_ID.toString(),
            )
        )
        database.bookshelfDao().insertBookshelf(
            BookshelfEntity(
                id = BOOKSHELF_ID,
                name = "Benchmark Shelf",
                sortType = "default",
                sortReversed = false,
                autoCache = false,
                systemUpdateReminder = false,
                allBookIds = listOf(BOOK_ID),
                pinnedBookIds = emptyList(),
                updatedBookIds = listOf(BOOK_ID),
            )
        )
        database.bookshelfDao().insertBookshelfBookMetadata(
            BookshelfBookMetadataEntity(
                id = BOOK_ID,
                lastUpdate = now,
                bookShelfIds = listOf(BOOKSHELF_ID),
            )
        )

        val today = LocalDate.now()
        database.bookRecordDao().insertBookRecord(
            BookRecordEntity(
                bookId = BOOK_ID,
                date = today,
                reads = 2,
                seconds = 3_600,
                isFavorited = true,
                firstSeen = LocalTime.of(9, 0),
                lastSeen = LocalTime.of(10, 0),
            )
        )
        val count = Count().apply {
            setMinute(9, 30)
            setMinute(10, 30)
        }
        database.dailyCountDao().insert(DailyCountEntity(today, count))
    }

    companion object {
        const val ACTION_SEED = "indi.renakoni.nextvol.benchmark.SEED"
        const val ACTION_SEED_SOURCE = "indi.renakoni.nextvol.benchmark.SEED_SOURCE"
        const val ACTION_SPEECH_ENGINE = "indi.renakoni.nextvol.benchmark.SPEECH_ENGINE"
        const val ACTION_SPEECH_SOURCE = "indi.renakoni.nextvol.benchmark.SPEECH_SOURCE"
        // The built-in Wenku8 source parses book IDs as integers when it
        // performs its background refresh, so the fixture ID must be numeric.
        private val book = BookIdentity.book("9999999")
        val BOOK_ID = book.storageKey
        val VOLUME_ID = BookIdentity.volumeKey(book, "benchmark-volume")
        val SECOND_VOLUME_ID = BookIdentity.volumeKey(book, "benchmark-volume-2")
        val CHAPTER_ONE_ID = SourceChapterId(book, "benchmark-chapter-1").storageKey
        val CHAPTER_TWO_ID = SourceChapterId(book, "benchmark-chapter-2").storageKey
        const val BOOKSHELF_ID = 1_000_001

        private val LONG_TEXT = buildString {
            repeat(30) { paragraph ->
                append("Benchmark paragraph ")
                append(paragraph + 1)
                append(". This deterministic text exercises layout, scrolling, pagination, progress, and formatting. ")
                append("It contains enough content to span multiple reader pages.\n\n")
            }
        }
    }
}
