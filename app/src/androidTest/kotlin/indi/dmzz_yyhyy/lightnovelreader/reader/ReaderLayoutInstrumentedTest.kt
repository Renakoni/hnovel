package indi.dmzz_yyhyy.lightnovelreader.reader

import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.theme.AppTheme
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalAppTheme
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.*
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.*
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.ReaderPage
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip.paginateReaderComponents
import indi.dmzz_yyhyy.lightnovelreader.utils.loadReaderFontFamilySafe
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: LightNovelReaderDatabase
    private lateinit var settings: SettingState
    private val importedFiles = mutableListOf<File>()

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, LightNovelReaderDatabase::class.java).build()
        settings = SettingState(UserDataRepository(database.userDataDao()), scope)
    }

    @After fun close() { scope.cancel(); database.close(); importedFiles.forEach { it.delete() } }

    @Test fun nativeFontsKeepPageFragmentsWithinMeasuredHeightAndRetainAllSourceOffsets() {
        val source = ReaderTextSource(0, "\n午后的阳光落在书页上。Mixed English and 中文 text, with punctuation! 𠮷🙂 ".repeat(8) +
            "\r\n\nA soft\u2028line break and a final paragraph.\n")
        for (fontScale in listOf(1f, 1.8f)) for (font in ReaderFont.entries.map { it.family() }) {
            val density = Density(1.5f, fontScale)
            val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
            val style = ReaderLayoutSettings.from(settings).textStyle(font, LocaleList("zh-CN,en-US"))
            for (width in listOf(90, 240, 620)) for (height in listOf(1, 100, 500)) {
                val pages = layoutReaderText(listOf(source), width, height, 13, style, measurer)
                val fragments = pages.flatten()
                assertEquals(source.text, fragments.joinToString("") { source.text.substring(it.start, it.end) })
                pages.forEach { page ->
                    assertEquals(0, page.first().spacingBefore)
                    val actualHeight = page.sumOf { fragment ->
                        val actual = measurer.measure(fragment.text, style, constraints = Constraints(maxWidth = width))
                        assertEquals("fragment reflow: width=$width height=$height text=${fragment.text}", fragment.height, actual.size.height)
                        actual.size.height + fragment.spacingBefore
                    }
                    assertTrue("page overflow: $actualHeight > $height", actualHeight <= height || page.size == 1)
                }
            }
        }
    }

    @Test fun changingFontsAndWidthKeepsTheOriginalCharacterAnchor() = runBlocking {
        val text = (1..80).joinToString("\n") { "第 $it 段：午后的阳光落在书页上。A quiet afternoon in the library." }
        val components = listOf(SimpleTextComponent(SimpleTextComponentData(text), UserDataRepository(database.userDataDao()), context))
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1.5f), LayoutDirection.Ltr)
        val initial = ReaderLayoutSettings.from(settings)
        val original = paginateReaderComponents(components, 400, 300,
            ReaderTextLayoutInput(initial, initial.textStyle(FontFamily.Default, LocaleList("zh-CN")), measurer, 0))
        val anchor = (original[original.size / 2] as ReaderPage).anchor
        for (font in ReaderFont.entries) {
            val next = initial.copy(fontSize = 22f, paragraphSpacing = 9f, fontUri = font.uri)
            val pages = paginateReaderComponents(components, 360, 230,
                ReaderTextLayoutInput(next, next.textStyle(font.family(), LocaleList("zh-CN")), measurer, 14))
            val containing = pages.filterIsInstance<ReaderPage>().filter { it.contains(anchor) }
            assertEquals(1, containing.size)
            assertTrue(containing.single().ranges.any { anchor.offset in it.start until it.end })
        }
    }

    @Test fun presetsPersistInTheExistingFontKeyAndMissingCustomFontFallsBack() = runBlocking {
        for (font in ReaderFont.entries) {
            settings.fontFamilyUriUserData.set(font.uri)
            assertEquals(font.uri, settings.fontFamilyUriUserData.get())
            val family = loadReaderFontFamilySafe(font.uri)
            if (font != ReaderFont.System) assertNotNull(family)
        }
        val invalid = File(context.cacheDir, "invalid-reader-font.ttf")
        try {
            invalid.writeText("not a font")
            assertNull(loadReaderFontFamilySafe(Uri.fromFile(invalid)))
            assertNull(loadReaderFontFamilySafe(Uri.fromFile(File(context.cacheDir, "missing-reader-font.ttf"))))
        } finally {
            invalid.delete()
        }
    }

    @Test fun layoutControlsPreviewWhileDraggingAndPersistIndependentSpacing() {
        val imported = File.createTempFile("reader-font-test-", ".otf", context.filesDir).also(importedFiles::add)
        context.resources.openRawResource(R.font.source_han_serif_regular).use { input ->
            imported.outputStream().use { input.copyTo(it) }
        }
        runBlocking { settings.fontFamilyUriUserData.set(Uri.fromFile(imported)) }
        compose.waitUntil(5_000) { settings.fontFamilyUri == Uri.fromFile(imported) }
        var dark by mutableStateOf(false)
        compose.setContent {
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                CompositionLocalProvider(
                    LocalAppTheme provides AppTheme(dark, colors),
                    LocalReaderStyle provides ReaderStyle(15f, 7f, 500f, Color.Black, Color.White),
                ) {
                    ReaderLayoutSettingsPage(settings)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.reader_font_system)).performScrollTo().performClick()
        compose.waitUntil(5_000) { settings.fontFamilyUri == Uri.EMPTY && !imported.exists() }
        val fontSlider = compose.onNodeWithContentDescription(context.getString(R.string.settings_reader_font_size))
        fontSlider.performScrollTo().performTouchInput { down(center); moveTo(centerRight) }
        val previewLayouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node -> node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(previewLayouts) }
        assertTrue(previewLayouts.any { it.layoutInput.style.fontSize.value > 15f && it.layoutInput.text.text.length > 20 })
        assertEquals(15f, settings.fontSize)
        fontSlider.performTouchInput { up() }
        compose.waitUntil(5_000) { settings.fontSize > 15f }
        val paragraph = compose.onNodeWithContentDescription(context.getString(R.string.reader_paragraph_spacing))
        paragraph.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        compose.waitUntil(5_000) { settings.paragraphSpacing == 8f }
        assertEquals(7f, settings.fontLineHeight)
        runBlocking {
            assertEquals(8f, settings.paragraphSpacingUserData.get())
            settings.fontSizeUserData.set(18f)
        }
        compose.waitUntil(5_000) { settings.fontSize == 18f }
        compose.onNodeWithText(context.getString(R.string.reader_font_wenkai)).performScrollTo().performClick()
        compose.waitUntil(5_000) { settings.fontFamilyUri == ReaderFont.WenKai.uri }
        saveScreenshot("reader-layout-light.png")
        compose.runOnIdle { dark = true }
        saveScreenshot("reader-layout-dark.png")
    }

    @Test fun compactLayoutKeepsSpacingAndMarginControlsReachable() {
        runBlocking { settings.autoPaddingUserData.set(false) }
        compose.waitUntil(5_000) { !settings.autoPadding }
        compose.setContent {
            val colors = lightColorScheme()
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                CompositionLocalProvider(LocalAppTheme provides AppTheme(false, colors)) {
                    Box(Modifier.width(600.dp).height(250.dp)) { ReaderLayoutSettingsPage(settings) }
                }
            }
        }
        val paragraph = compose.onNodeWithContentDescription(context.getString(R.string.reader_paragraph_spacing))
            .performScrollTo().assertIsDisplayed()
        val density = context.resources.displayMetrics.density
        fun assertFullyVisible(slider: SemanticsNodeInteraction) {
            val visible = slider.fetchSemanticsNode().boundsInRoot
            val unclipped = slider.getUnclippedBoundsInRoot()
            val fullHeight = (unclipped.bottom - unclipped.top).value * density
            assertTrue("The complete slider must be visible: $visible, full height=$fullHeight", visible.height + 1f >= fullHeight)
        }
        saveScreenshot("reader-layout-compact.png")
        assertFullyVisible(paragraph)
        paragraph.performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.waitUntil(5_000) { settings.paragraphSpacing == 9f }
        val bottom = compose.onNodeWithContentDescription(context.getString(R.string.settings_reader_bottom_margin))
            .performScrollTo().assertIsDisplayed()
        assertFullyVisible(bottom)
        bottom.performSemanticsAction(SemanticsActions.SetProgress) { it(23f) }
        compose.waitUntil(5_000) { settings.bottomPadding == 23f }
        assertEquals(7f, settings.fontLineHeight)
        saveScreenshot("reader-layout-compact.png")
    }

    private fun saveScreenshot(name: String) {
        fun clearFocus(node: AccessibilityNodeInfo?) {
            node ?: return
            if (node.isAccessibilityFocused) node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            for (index in 0 until node.childCount) clearFocus(node.getChild(index))
        }
        // Preserve device accessibility settings; clear only the transient focus decoration.
        clearFocus(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
