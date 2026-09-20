package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ExportBottomSheetTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun deselectingLastVolumeKeepsSplitModeAndDisablesExport() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        val settings = mutableStateOf(ExportSettings(setOf("v1"), exportType = ExportType.VOLUMES))
        var exported: ExportSettings? = null
        try {
            activity.get().setContent {
                MaterialTheme {
                    ExportBottomSheet(rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        BookVolumes("book", listOf(Volume("v1", "Volume one", emptyList()))),
                        settings.value, { settings.value = it }, {}, { exported = it })
                }
            }
            compose.onNodeWithText("Volume one").performClick()
            compose.onNodeWithText(activity.get().getString(R.string.export)).assertIsNotEnabled()
            assertEquals(ExportType.VOLUMES, settings.value.exportType)
            assertTrue(settings.value.selectedVolumeIds.isEmpty())
            assertNull(exported)
            compose.onNodeWithText("Volume one").performClick()
            compose.onNodeWithText(activity.get().getString(R.string.export)).assertIsEnabled().performClick()
            assertEquals(setOf("v1"), exported!!.selectedVolumeIds)
            assertEquals(ExportType.VOLUMES, exported!!.exportType)
        } finally { activity.pause().stop().destroy() }
    }
}
