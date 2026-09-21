package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
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
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderEntryNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var nav: NavHostController
    private lateinit var homeEntryId: String
    private val book = SourceBookId(Identifier("fixture", "reader-entry"), "book")

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        activity.get().setContent { MaterialTheme {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Main,
                enterTransition = { fadeIn(tween(300)) }, exitTransition = { fadeOut(tween(300)) },
                popEnterTransition = { fadeIn(tween(300)) }, popExitTransition = { fadeOut(tween(300)) }) {
                navigation<Route.Main>(startDestination = Route.Main.Reading) {
                    navigation<Route.Main.Reading>(startDestination = Route.Main.Reading.Home) {
                        composable<Route.Main.Reading.Home> {
                            Column {
                                Button(onClick = { openFromHome("chapter-1") }) { Text("Continue reading") }
                                Button(onClick = { openFromHome("chapter-2") }) { Text("Choose chapter 2") }
                            }
                        }
                    }
                }
                navigation<Route.Book>(startDestination = Route.Book.Detail(book.storageKey)) {
                    composable<Route.Book.Detail> {
                        Column {
                            Button(onClick = { openFromDetail("chapter-1") }) { Text("Start reading") }
                            Button(onClick = { openFromDetail("chapter-2") }) { Text("Chapter 2") }
                        }
                    }
                    composable<Route.Book.Reader> { Text(it.toRoute<Route.Book.Reader>().chapterId) }
                }
            }
        } }
        compose.waitForIdle()
        homeEntryId = nav.currentBackStackEntry!!.id
    }

    @After fun destroy() {
        compose.mainClock.autoAdvance = true
        activity.pause().stop().destroy()
    }

    private fun openFromDetail(chapterId: String) {
        nav.navigateToBookReaderDestination(book.storageKey, chapterId, activity.get())
    }

    private fun openFromHome(chapterId: String) {
        nav.navigateToBookReaderDestination(book.storageKey, chapterId, activity.get(), includeDetail = true)
    }

    private fun showDetail(): String {
        compose.runOnIdle { nav.navigateToBookDetailDestination(book.storageKey) }
        compose.waitForIdle()
        return nav.currentBackStackEntry!!.id
    }

    private fun clickAction(text: String): () -> Boolean =
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

    private fun back() {
        compose.runOnIdle { nav.popBackStackIfResumed() }
        compose.waitForIdle()
    }

    private fun assertReader(chapterId: String) {
        compose.waitForIdle()
        assertEquals(Route.Book.Reader(book.storageKey, BookIdentity.chapter(chapterId, book).storageKey),
            nav.currentBackStackEntry!!.toRoute<Route.Book.Reader>())
    }

    private fun assertDetail() {
        assertTrue(nav.currentDestination!!.hasRoute<Route.Book.Detail>())
        assertEquals(book.storageKey, nav.currentBackStackEntry!!.toRoute<Route.Book.Detail>().bookId)
    }

    @Test fun detailSameFrameClicksOpenOnceAndAllowReadingAgainAfterBack() {
        val detailEntryId = showDetail()
        val read = clickAction("Start reading")
        compose.runOnIdle { repeat(5) { read() } }
        assertReader("chapter-1")
        back()
        assertEquals(detailEntryId, nav.currentBackStackEntry!!.id)
        compose.onNodeWithText("Chapter 2").performClick()
        assertReader("chapter-2")
        back()
        assertEquals(detailEntryId, nav.currentBackStackEntry!!.id)
        back()
        assertEquals(homeEntryId, nav.currentBackStackEntry!!.id)
    }

    @Test fun homeSameFrameClicksKeepOneDetailAndAllowReadingAgainAfterBack() {
        val read = clickAction("Continue reading")
        compose.runOnIdle { repeat(5) { read() } }
        assertReader("chapter-1")
        back()
        assertDetail()
        back()
        assertEquals(homeEntryId, nav.currentBackStackEntry!!.id)
        compose.onNodeWithText("Choose chapter 2").performClick()
        assertReader("chapter-2")
        back()
        assertDetail()
        back()
        assertEquals(homeEntryId, nav.currentBackStackEntry!!.id)
    }

    private fun assertRepeatedClickDuringTransitionIsIgnored(text: String) {
        val read = clickAction(text)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { read() }
        compose.mainClock.advanceTimeBy(32)
        assertFalse(nav.isResumed())
        val readerEntryId = nav.currentBackStackEntry!!.id
        compose.runOnIdle { repeat(5) { read() } }
        assertEquals(readerEntryId, nav.currentBackStackEntry!!.id)
        compose.mainClock.autoAdvance = true
        assertReader("chapter-1")
    }

    @Test fun detailRepeatedClicksDuringTransitionKeepTheIncomingReader() {
        showDetail()
        assertRepeatedClickDuringTransitionIsIgnored("Start reading")
    }

    @Test fun homeRepeatedClicksDuringTransitionKeepTheIncomingReader() {
        assertRepeatedClickDuringTransitionIsIgnored("Continue reading")
    }

    @Test fun detailLateChapterRequestDoesNotReplaceTheResumedReader() {
        val detailEntryId = showDetail()
        compose.onNodeWithText("Start reading").performClick()
        assertReader("chapter-1")
        assertTrue(nav.isResumed())
        val readerEntryId = nav.currentBackStackEntry!!.id
        compose.runOnIdle { openFromDetail("chapter-2") }
        assertEquals(readerEntryId, nav.currentBackStackEntry!!.id)
        assertReader("chapter-1")
        back()
        assertEquals(detailEntryId, nav.currentBackStackEntry!!.id)
    }

    @Test fun homeLateChapterRequestDoesNotAddAnotherDetailOrReader() {
        compose.onNodeWithText("Continue reading").performClick()
        assertReader("chapter-1")
        assertTrue(nav.isResumed())
        val readerEntryId = nav.currentBackStackEntry!!.id
        compose.runOnIdle { openFromHome("chapter-2") }
        assertEquals(readerEntryId, nav.currentBackStackEntry!!.id)
        assertReader("chapter-1")
        back()
        assertDetail()
        back()
        assertEquals(homeEntryId, nav.currentBackStackEntry!!.id)
    }

    @Test fun enteringDetailMustResumeBeforeAcceptingAReadRequest() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigateToBookDetailDestination(book.storageKey) }
        compose.mainClock.advanceTimeBy(32)
        assertFalse(nav.isResumed())
        compose.runOnIdle { openFromDetail("chapter-1") }
        assertDetail()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Start reading").performClick()
        assertReader("chapter-1")
    }
}
