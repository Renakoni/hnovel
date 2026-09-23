package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.explore.home.ExploreHomeScreen
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreSectionTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun longTitlesStayAboveBooksAndKeepTheirFullAccessibleText() = checkTitles(listOf(
        "每周热门轻小说排行榜与编辑精选推荐作品包含异世界冒险校园青春恋爱喜剧以及最新连载作品".repeat(3),
        "The most popular light novels and editors' recommendations from this week's new releases ".repeat(3),
        "✨【本周精选】📚──热门轻小说推荐榜单──★异世界冒险与青春校园★──".repeat(3)
    ), long = true)

    @Test fun shortTitlesKeepCompactSpacingWithAndWithoutMore() = checkTitles(listOf("热门榜"), long = false)

    private fun checkTitles(titles: List<String>, long: Boolean) {
        val sourceId = Identifier("fixture", "Source")
        val source = SourceListing(SourceMetadata(WebDataSourceItem(sourceId, "Source", "fixture"),
            setOf(SourceCapability.Explore)), SourceStatus.Ready)
        val book = SourceDiscoveryBook(SourceBookId(sourceId, "book"), "Preview book", "", "")
        var section by mutableStateOf(SourceDiscoverySection("list", titles.first(), listOf(book), more = null))
        var scale by mutableFloatStateOf(1f)
        var opened: SourceDiscoverySection? = null
        var openedBook: SourceBookId? = null
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MaterialTheme(typography = AppTypography) {
                    ExploreHomeScreen(DiscoveryPageState(listOf(source), sourceId,
                        mapOf(sourceId to DiscoveryPageContent(loaded = true, sections = listOf(section)))),
                        {}, { _, _ -> }, {}, { opened = it }, { openedBook = it }, {}, {}, { _, _ -> }, { _, _ -> }, {})
                }
            }
        }
        for (fontScale in listOf(1f, 1.3f, 1.5f, 2f)) for (title in titles) {
            val previewTops = mutableListOf<Float>()
            for (hasMore in listOf(false, true)) {
                compose.runOnIdle {
                    scale = fontScale
                    section = section.copy(title = title, more = if (hasMore) SourceDiscoveryTarget(sourceId, "/list") else null)
                }
                // Exact text matching also verifies that ellipsis does not truncate accessibility text.
                val label = compose.onNodeWithText(title).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals(if (long) 2 else 1, layout.lineCount)
                assertEquals(long, layout.isLineEllipsized(layout.lineCount - 1))
                assertTrue("all rendered lines must fit vertically at $fontScale",
                    layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 0.5f)
                val titleBounds = label.fetchSemanticsNode().boundsInRoot
                val preview = compose.onNode(hasClickAction() and hasText(book.title)).assertIsDisplayed()
                val previewBounds = preview.fetchSemanticsNode().boundsInRoot
                assertTrue("title must end above the book preview", titleBounds.bottom <= previewBounds.top)
                assertTrue("title must stay inside the screen", titleBounds.left >= 0 && titleBounds.right <= 360)
                previewTops += previewBounds.top
                val more = compose.onNodeWithContentDescription("Show more")
                if (hasMore) {
                    more.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    val moreBounds = more.fetchSemanticsNode().boundsInRoot
                    assertTrue("title and more need separate space", titleBounds.right < moreBounds.left)
                    assertTrue("more must end above the book preview", moreBounds.bottom <= previewBounds.top)
                    more.performClick()
                    assertEquals(section, opened)
                    assertEquals(title, opened!!.title)
                } else more.assertDoesNotExist()
                preview.performClick()
                assertEquals(book.id, openedBook)
            }
            assertEquals("adding more must not shift this preview", previewTops[0], previewTops[1], 0.5f)
        }
    }
}
