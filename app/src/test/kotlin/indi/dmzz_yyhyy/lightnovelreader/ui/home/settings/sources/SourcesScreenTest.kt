package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sources

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SourcesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val model = mockk<SourcesViewModel>(relaxed = true)
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun emptyStateOpensExplicitPreviewFlowWithoutImportingAutomatically() {
        activity.get().setContent { MaterialTheme { SourcesScreen(SourceManagementState(), model, onDiagnostics = {}) {} } }
        compose.onNodeWithText("Add book source").performClick()
        compose.onNodeWithText("Source file URL").performTextInput("https://fixture.invalid/source.json")
        compose.onNodeWithText("Download and preview").performClick()
        verify(exactly = 1) { model.previewUrl("https://fixture.invalid/source.json") }
        verify(exactly = 0) { model.commit(any(), any(), any()) }
        compose.onNodeWithText("Choose local file").assertExists()
        compose.onNodeWithText("Paste source JSON").assertExists()
    }
}
