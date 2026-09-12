package indi.dmzz_yyhyy.lightnovelreader.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import indi.dmzz_yyhyy.lightnovelreader.ui.home.categories.CategorySourceSelection
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.SettingsTopBar
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.navigateToSettingsDestination
import indi.dmzz_yyhyy.lightnovelreader.utils.currentMainRoute
import indi.dmzz_yyhyy.lightnovelreader.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalMaterial3Api::class)
class MainNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var nav: NavHostController
    private val lists = mutableMapOf<String, LazyListState>()
    private val roots = listOf(Route.Main.Reading, Route.Main.Bookshelf, Route.Main.Explore, Route.Main.Categories())
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        show()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun show() {
        activity.get().setContent { MaterialTheme {
            val controller = rememberNavController()
            nav = controller
            val entry by controller.currentBackStackEntryAsState()
            val root = entry?.destination.currentMainRoute()
            Box(Modifier.fillMaxSize()) {
                NavHost(controller, startDestination = Route.Main,
                    enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                    navigation<Route.Main>(startDestination = Route.Main.Reading) {
                        navigation<Route.Main.Reading>(startDestination = Route.Main.Reading.Home) {
                            composable<Route.Main.Reading.Home> { RootPage("Reading", it) }
                        }
                        navigation<Route.Main.Bookshelf>(startDestination = Route.Main.Bookshelf.Home) {
                            composable<Route.Main.Bookshelf.Home> { RootPage("Bookshelf", it) }
                        }
                        navigation<Route.Main.Explore>(startDestination = Route.Main.Explore.Home) {
                            composable<Route.Main.Explore.Home> { RootPage("Explore", it) }
                            composable<Route.Main.Explore.Search> { Text("Search") }
                        }
                        composable<Route.Main.Categories> { RootPage("Categories", it, categories = true) }
                        composable<Route.Main.DiscoveryResults> { Text("Results") }
                        navigation<Route.Main.Settings>(startDestination = Route.Main.Settings.Home) {
                            composable<Route.Main.Settings.Home> {
                                Column {
                                    SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior()) { nav.popBackStackIfResumed() }
                                    Button(onClick = { nav.navigate(Route.Main.Settings.Sources) }) { Text("Manage sources") }
                                }
                            }
                            composable<Route.Main.Settings.Sources> {
                                Button(onClick = { nav.popBackStackIfResumed() }) { Text("Back to settings") }
                            }
                        }
                    }
                }
                if (root != null) Box(Modifier.align(Alignment.BottomCenter)) { HomeNavigateBar(root, nav) }
            }
        } }
        compose.waitForIdle()
    }

    @Composable
    private fun RootPage(title: String, entry: NavBackStackEntry, categories: Boolean = false) {
        var source by rememberSaveable { mutableStateOf("a") }
        val list = rememberLazyListState()
        lists[title] = list
        if (categories) CategorySourceSelection(entry, ready = true) { source = it.id }
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$title root", Modifier.weight(1f))
                HomeSettingsAction { nav.navigateToSettingsDestination() }
            }
            Text("Selected source: $source")
            Button(onClick = { source = "b" }) { Text("Select source B") }
            LazyColumn(Modifier.testTag("root-list"), state = list, contentPadding = PaddingValues(bottom = 100.dp)) {
                items((0 until 40).toList()) { index -> Text("Row $index", Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
    }

    private fun go(route: Any) {
        compose.runOnIdle { nav.navigateToMainRoot(route) }
        compose.waitForIdle()
    }

    @Test fun fourRootsUseOneSettingsDestinationAndDoubleClicksReturnToExactOrigin() {
        for (route in roots) {
            go(route)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(4)
            val origin = compose.runOnIdle { nav.currentBackStackEntry!!.id }
            compose.runOnIdle { nav.navigateToSettingsDestination(); nav.navigateToSettingsDestination() }
            compose.waitForIdle()
            assertTrue(nav.currentDestination!!.hasRoute<Route.Main.Settings.Home>())
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(0)
            compose.onNodeWithText("Manage sources").performClick()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(0)
            compose.onNodeWithText("Back to settings").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            assertEquals(origin, nav.currentBackStackEntry!!.id)
            assertEquals(route, nav.currentDestination.currentMainRoute())
        }
    }

    @Test fun categoryShortcutAndBottomTabReuseEntryButOnlyShortcutChangesSource() {
        go(Route.Main.Categories("fixture", "a"))
        val categoryEntry = nav.currentBackStackEntry!!.id
        compose.onNodeWithText("Selected source: a").assertExists()
        compose.onNodeWithText("Select source B").performClick()
        compose.runOnIdle { runBlocking { lists.getValue("Categories").scrollToItem(12, 7) } }
        go(Route.Main.Explore)
        go(Route.Main.Categories())
        assertEquals(categoryEntry, nav.currentBackStackEntry!!.id)
        compose.onNodeWithText("Selected source: b").assertExists()
        assertEquals(12, lists.getValue("Categories").firstVisibleItemIndex)
        go(Route.Main.Explore)
        go(Route.Main.Categories("fixture", "a"))
        assertEquals(categoryEntry, nav.currentBackStackEntry!!.id)
        compose.onNodeWithText("Selected source: a").assertExists()
        assertNull(nav.currentBackStackEntry!!.savedStateHandle.get<String>(CATEGORY_SOURCE_REQUEST))
        go(Route.Main.Categories())
        assertEquals(categoryEntry, nav.currentBackStackEntry!!.id)
    }

    @Test fun settingsAndNestedSourcePageSurviveRecreationThenRestoreCategorySelectionAndScroll() {
        go(Route.Main.Categories())
        compose.onNodeWithText("Select source B").performClick()
        compose.runOnIdle { runBlocking { lists.getValue("Categories").scrollToItem(9, 13) } }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Manage sources").performClick()
        val savedEntry = nav.currentBackStackEntry!!.id
        compose.runOnIdle {
            activity.recreate()
            activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        }
        show()
        assertEquals(savedEntry, nav.currentBackStackEntry!!.id)
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(nav.currentDestination!!.hasRoute<Route.Main.Categories>())
        compose.onNodeWithText("Selected source: b").assertExists()
        assertEquals(9, lists.getValue("Categories").firstVisibleItemIndex)
        assertEquals(13, lists.getValue("Categories").firstVisibleItemScrollOffset)
    }

    @Test fun searchAndResultsAreSecondaryWhileSourceQualifiedCategoryRouteIsARoot() {
        go(Route.Main.Categories("fixture", "a"))
        assertTrue(nav.currentDestination.currentMainRoute() is Route.Main.Categories)
        compose.runOnIdle { nav.navigate(Route.Main.Explore.Search("fixture", "a")) }
        compose.waitForIdle()
        assertNull(nav.currentDestination.currentMainRoute())
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(Route.Main.DiscoveryResults("fixture", "a", "all", "All", "session")) }
        compose.waitForIdle()
        assertNull(nav.currentDestination.currentMainRoute())
    }
}
