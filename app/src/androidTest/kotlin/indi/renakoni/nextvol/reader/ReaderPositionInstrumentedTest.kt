package indi.renakoni.nextvol.reader

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.content.ContentComponentFactory
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.tts.SpeechPosition
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkPosition
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
import indi.renakoni.nextvol.ui.book.reader.content.*
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderContentAnchor
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentUiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Source offsets, not equal page numbers/progress, are the contract under test. */
@RunWith(AndroidJUnit4::class)
class ReaderPositionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: NextVolDatabase
    private lateinit var settings: SettingState

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        settings = SettingState(UserDataRepository(database.userDataDao()), scope)
        runBlocking {
            settings.isUsingFlipPageUserData.set(true)
            settings.isUsingContinuousScrollingUserData.set(false)
            settings.fontSizeUserData.set(20f)
        }
    }

    @After fun close() {
        ReaderLayoutTestActivity.installReader = null
        compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
        scope.cancel()
        database.close()
    }

    @Test fun sourceCharacterSurvivesFlipChapterScrollAndContinuousScrollRoundTrip() {
        val fixture = Fixture()
        fixture.assertPosition()
        fixture.setMode(flip = false, continuous = false)
        fixture.assertPosition()
        fixture.setMode(flip = false, continuous = true)
        fixture.assertPosition()
        fixture.setMode(flip = true, continuous = true)
        fixture.assertPosition()
    }

    @Test fun repeatedFontSpacingPaddingAndWidthChangesDoNotRoundTheLogicalAnchor() {
        val fixture = Fixture()
        for (flip in listOf(true, false)) {
            fixture.setMode(flip, continuous = !flip)
            for ((width, font, spacing, padding) in listOf(
                listOf(280, 26, 12, 9), listOf(450, 18, 6, 16), listOf(320, 20, 0, 0),
            )) {
                compose.runOnIdle { fixture.width = width.dp; fixture.padding = PaddingValues(padding.dp) }
                runBlocking { settings.fontSizeUserData.set(font.toFloat()); settings.fontLineHeightUserData.set(spacing.toFloat()) }
                compose.waitUntil(20_000) { textLayouts().any { it.layoutInput.style.fontSize.value == font.toFloat() } }
                fixture.awaitReady()
                fixture.assertPosition(description = "flip=$flip width=$width font=$font spacing=$spacing padding=$padding")
            }
        }
    }

    @Test fun activityRecreateRetainsRealViewModelButReinstallsEachRendererAtSourceOffset() {
        val fixture = Fixture()
        val original = fixture.reader
        for (flip in listOf(true, false)) {
            fixture.setMode(flip, continuous = !flip)
            val oldActivity = compose.activity
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            fixture.awaitReady()
            assertNotSame(oldActivity, compose.activity)
            assertSame(original, fixture.reader)
            assertEquals(1, fixture.createdViewModels)
            fixture.assertPosition()
        }
    }

    @Test fun speechTargetWinsOverPassiveCheckpointAcrossModes() {
        val fixture = Fixture(speechTarget = true)
        fixture.assertPosition(fixture.speechOffset)
        fixture.setMode(flip = false, continuous = true)
        fixture.assertPosition(fixture.speechOffset)
    }

    @Test fun invalidSavedFingerprintFallsBackAndFinishesInFlip() {
        val fixture = Fixture(invalid = true)
        assertNull(fixture.reader.positions.pending)
        assertEquals(0, (fixture.reader.uiState.contentUiState as FlipPageContentUiState).pagerState.settledPage)
    }

    @Test fun invalidSavedFingerprintFallsBackAndFinishesInScroll() {
        runBlocking { settings.isUsingFlipPageUserData.set(false) }
        val fixture = Fixture(invalid = true)
        assertNull(fixture.reader.positions.pending)
        assertFalse((fixture.reader.uiState.contentUiState as ScrollContentUiState).isRestoringProgress)
        assertTrue((fixture.reader.positions.checkpoint?.position?.offset ?: 0) < fixture.target)
    }

    @Test fun emptyChapterTerminatesRecoveryInBothModes() {
        val fixture = Fixture(empty = true)
        assertNull(fixture.reader.positions.pending)
        fixture.setMode(flip = false, continuous = false)
        assertFalse((fixture.reader.uiState.contentUiState as ScrollContentUiState).isRestoringProgress)
    }

    @Test fun failedChapterTerminatesRecoveryInBothModes() {
        val fixture = Fixture(failed = true)
        assertNull(fixture.reader.positions.pending)
        fixture.setMode(flip = false, continuous = false)
        assertFalse((fixture.reader.uiState.contentUiState as ScrollContentUiState).isRestoringProgress)
    }

    @Test fun flipReflowAndBackgroundKeepTheSavedProgress() = reflowAndBackground(true, false)
    @Test fun chapterScrollReflowAndBackgroundKeepTheSavedProgress() = reflowAndBackground(false, false)
    @Test fun continuousScrollReflowAndBackgroundKeepTheSavedProgress() = reflowAndBackground(false, true)

    @Test fun cancelledBookmarkRestorationDoesNotLeaveScrollWritesBlocked() {
        runBlocking { settings.isUsingFlipPageUserData.set(false) }
        val fixture = Fixture(initialProgress = 0.6f, interceptRestore = true)
        val state = fixture.reader.uiState.contentUiState as ScrollContentUiState
        val bookmark = ReaderBookmarkPosition("fixture-book", fixture.chapter,
            ReaderContentAnchor(0, fixture.speechOffset), 0.8f).bookmark()
        compose.runOnIdle {
            fixture.interceptRestoration = {
                fixture.interceptRestoration = null
                fixture.bookmarks.pending = bookmark.copy(fingerprint = "changed-content")
            }
            fixture.bookmarks.pending = bookmark
        }
        compose.waitUntil(15_000) { fixture.bookmarks.notice != null &&
            fixture.bookmarks.pending == null && !state.isRestoringProgress }
        assertTrue("Cancelled placement is not new reading", fixture.progressWrites.isEmpty())
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.waitUntil(15_000) { fixture.progressWrites.isNotEmpty() }
        assertTrue(fixture.progressWrites.last().isFinite())
    }

    private fun reflowAndBackground(flip: Boolean, continuous: Boolean) {
        runBlocking {
            settings.isUsingContinuousScrollingUserData.set(continuous)
            settings.isUsingFlipPageUserData.set(flip)
        }
        val fixture = Fixture(initialProgress = 0.6f)
        fixture.assertPosition()
        val before = fixture.reader.uiState.contentUiState!!.readingProgress
        compose.runOnIdle { fixture.width = 900.dp; fixture.height = 560.dp }
        fixture.awaitReady()
        fixture.assertPosition()
        val after = fixture.reader.uiState.contentUiState!!.readingProgress
        assertNotEquals("Reflow must actually change the displayed extent", before, after)
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val saved = fixture.savedProgress()
            assertTrue("Reflow/ON_STOP must not publish new reading: ${fixture.progressWrites}", fixture.progressWrites.isEmpty())
            assertEquals(0.6f, saved.currentChapterReadingProgressMap.getValue(fixture.chapter.id))
            assertEquals(0.6f, saved.maxChapterReadingProgressMap.getValue(fixture.chapter.id))
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
        fixture.awaitReady()
        assertTrue(fixture.progressWrites.isEmpty())
        if (flip) compose.onRoot().performTouchInput { swipeLeft() }
        else compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.waitUntil(15_000) { fixture.progressWrites.isNotEmpty() }
        assertTrue("Real reading must resume persistence", fixture.savedProgress()
            .maxChapterReadingProgressMap.getValue(fixture.chapter.id) > 0.6f)
    }

    private inner class Fixture(
        val empty: Boolean = false, val failed: Boolean = false, invalid: Boolean = false, speechTarget: Boolean = false,
        initialProgress: Float = 0f,
        interceptRestore: Boolean = false,
    ) {
        val text = (1..90).joinToString(10.toChar().toString()) { index ->
            "marker-${index.toString().padStart(3, '0')} 😀 " +
                "This paragraph keeps a unique original UTF-16 reading position across every layout. ".repeat(4)
        }
        val target = text.indexOf("marker-053") + 4
        val speechOffset = text.indexOf("marker-071") + 5
        val repository = UserDataRepository(database.userDataDao())
        val bookmarks = ReaderBookmarkSession()
        var interceptRestoration: (() -> Unit)? = null
        val chapter = ChapterContentUiState("marked-chapter", "Source positions",
            if (empty) emptyList() else listOf(SimpleTextComponent(SimpleTextComponentData(text), repository, context)), "before", "after")
        var width by mutableStateOf(320.dp)
        var height by mutableStateOf(420.dp)
        var padding by mutableStateOf(PaddingValues(0.dp))
        lateinit var reader: ReaderViewModel
        var createdViewModels = 0
        private val handle = SavedStateHandle()
        private val source = mockk<ChapterSource> {
            every { getBookVolumesFlow(any(), any()) } returns emptyFlow()
            every { getChapterContentFlow(any(), any(), any()) } answers {
                val id = firstArg<String>()
                val content = ChapterContent(
                    id, if (id == chapter.id) chapter.title else id,
                    buildJsonObject {
                        put("components", buildJsonArray {
                            if (!empty) add(buildJsonObject {
                                put("id", SimpleTextComponentData.id.toString())
                                put("data", SimpleTextComponentData(text).toJsonElement())
                            })
                        })
                    },
                    if (id == chapter.id) chapter.prevChapter else if (id == "after") chapter.id else null,
                    if (id == chapter.id) chapter.nextChapter else if (id == "before") chapter.id else null,
                )
                flowOf(if (failed) Err(WebRequestError("Fixture", "Expected load failure")) else Ok(content))
            }
            coEvery { preloadChapterContent(any(), any(), any()) } returns Unit
        }
        private val loader = ReaderChapterLoader(source, ContentRenderer(
            ContentJsonDecoder(ContentComponentRegistry()), ContentComponentFactory(context, repository),
        ))
        val progressWrites = java.util.Collections.synchronizedList(mutableListOf<Float>())
        private val statistics = StatsRepository(database.bookRecordDao(), database.dailyCountDao(),
            mockk(relaxed = true), StatisticsWriteCoordinator())
        private val records = object : BookReadingDataAccess {
            private var data = UserReadingData("fixture-book",
                currentChapterReadingProgressMap = mapOf(chapter.id to initialProgress),
                maxChapterReadingProgressMap = mapOf(chapter.id to initialProgress))
            override fun progressRevision() = 0L
            override suspend fun getUserReadingData(bookId: String) = synchronized(this) { data }
            override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
                synchronized(this) { data = update(data) }
            }
            override suspend fun updateChapterProgress(bookId: String, chapterId: String, revision: Long,
                update: (UserReadingData) -> UserReadingData): Boolean {
                updateUserReadingData(bookId, update)
                synchronized(this) { progressWrites += data.currentChapterReadingProgressMap.getValue(chapterId) }
                return true
            }
        }
        fun savedProgress() = runBlocking { records.getUserReadingData("fixture-book") }
        private val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                createdViewModels++
                val modes = ReaderModeFactory(loader, records)
                val factory = if (!interceptRestore) modes else mockk<ReaderModeFactory> {
                    every { create(any(), any(), any(), any()) } answers {
                        val mode = modes.create(firstArg(), secondArg(), thirdArg(), arg(3))
                        val state = mode.uiState
                        if (state !is ScrollContentUiState) mode else object : ReaderModeController by mode {
                            override val uiState = object : ScrollContentUiState by state {
                                override val onProgressRestoring: (androidx.compose.foundation.lazy.LazyListState) -> Unit = { list ->
                                    state.onProgressRestoring(list)
                                    interceptRestoration?.invoke()
                                }
                            }
                        }
                    }
                }
                return ReaderViewModel(statistics, source, records, repository,
                    factory, mockk(relaxed = true), handle) as T
            }
        }

        init {
            ReaderPositionSession(handle).restore(ReaderCheckpoint("fixture-book", chapter.id,
                ReaderPosition("fixture-book", chapter.id, 0, target, if (invalid) "changed-content" else chapter.bookmarkFingerprint)))
            ReaderLayoutTestActivity.installReader = { activity ->
                reader = ViewModelProvider(activity, factory)[ReaderViewModel::class.java]
                reader.openBook("fixture-book", "stale-route")
                activity.setContent {
                    val colors = lightColorScheme()
                    MaterialTheme(colorScheme = colors, typography = AppTypography) {
                        CompositionLocalProvider(
                            LocalAppTheme provides AppTheme(false, colors),
                            LocalReaderTextLayout provides rememberReaderTextLayout(reader.readerSettings),
                            LocalReaderPositionSession provides reader.positions,
                            LocalReaderBookmarks provides bookmarks,
                            LocalReaderSpeechFollow provides if (speechTarget) ReaderSpeechFollow(
                                SpeechPosition("fixture-book", chapter.id, chapter.speechTextIndex.fingerprint, speechOffset, speechOffset + 1),
                                following = true) else ReaderSpeechFollow(),
                        ) {
                            Box(Modifier.width(width).height(height)) {
                                when (val state = reader.uiState.contentUiState) {
                                    is FlipPageContentUiState -> FlipPageContentComponent(Modifier, state, reader.readerSettings, padding, {}, {}, {})
                                    is ScrollContentUiState -> ScrollContentComponent(Modifier, state, reader.readerSettings, reader.fontFamilySettings, padding, {}, {}, {})
                                }
                            }
                        }
                    }
                }
            }
            compose.activityRule.scenario.onActivity { ReaderLayoutTestActivity.installReader!!(it) }
            awaitReady()
        }

        fun awaitReady() {
            compose.waitForIdle()
            compose.waitUntil(30_000) {
                val state = reader.uiState.contentUiState
                state?.readingChapterId == chapter.id && state.readingChapterContent != null && reader.positions.pending == null && when (state) {
                    is FlipPageContentUiState -> empty || failed || state.pagerState.pageCount > 0
                    is ScrollContentUiState -> !state.isRestoringProgress &&
                        (failed || state.lazyListState.layoutInfo.visibleItemsInfo.any { it.key == chapter.id })
                    else -> false
                }
            }
            compose.waitForIdle()
        }

        fun setMode(flip: Boolean, continuous: Boolean) {
            runBlocking {
                settings.isUsingContinuousScrollingUserData.set(continuous)
                settings.isUsingFlipPageUserData.set(flip)
            }
            compose.waitUntil(15_000) {
                reader.readerSettings.isUsingContinuousScrolling == continuous &&
                    (if (flip) reader.uiState.contentUiState is FlipPageContentUiState else reader.uiState.contentUiState is ScrollContentUiState)
            }
            awaitReady()
        }

        fun assertPosition(offset: Int = target, description: String = "") {
            awaitReady()
            val position = reader.positions.captureNow()!!.position!!
            assertEquals(chapter.id, position.chapterId)
            assertEquals(0, position.componentIndex)
            assertEquals(description, offset, position.offset)
            val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes()
            val visible = compose.runOnIdle {
                nodes.any { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    layouts.any { layout ->
                        val start = text.indexOf(layout.layoutInput.text.text)
                        start >= 0 && offset in start until start + layout.layoutInput.text.length &&
                            node.boundsInRoot.overlaps(layout.getBoundingBox(offset - start).translate(node.positionInRoot))
                    }
                }
            }
            val diagnostics = if (visible) "" else compose.runOnIdle {
                nodes.mapNotNull { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                    layouts.firstOrNull()?.let { layout ->
                        val start = text.indexOf(layout.layoutInput.text.text)
                        if (start < 0) null else "source=$start bounds=${node.boundsInRoot} origin=${node.positionInRoot} size=${layout.size}"
                    }
                }.joinToString("; ")
            }
            val scroll = (reader.uiState.contentUiState as? ScrollContentUiState)?.lazyListState
            assertTrue("$description Original source offset $offset must be in the clipped visible viewport; list=${scroll?.firstVisibleItemIndex}/${scroll?.firstVisibleItemScrollOffset}: $diagnostics", visible)
        }
    }

    private fun textLayouts(): List<TextLayoutResult> {
        val layouts = mutableListOf<TextLayoutResult>()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        compose.runOnIdle {
            nodes.forEach { it.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) }
        }
        return layouts
    }
}
