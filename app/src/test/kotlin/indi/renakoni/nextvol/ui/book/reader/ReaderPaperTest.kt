package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaperTest {
    @Test fun absentAndUnknownPaperKeepTheExistingAppearance() {
        for (id in listOf("default", "", "future-paper")) {
            assertEquals(ReaderPaper.Default, ReaderPaper.fromId(id))
            assertNull(ReaderPaper.fromId(id).colors)
        }
    }

    @Test fun paperAndMenuTextStayReadableOnTheirActualSurfaces() {
        for (paper in ReaderPaper.entries) {
            val colors = paper.colors ?: continue
            val scheme = colors.colorScheme()
            assertTrue("${paper.id}: body", contrast(colors.text, colors.background) >= 7f)
            for (surface in listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                assertTrue("${paper.id}: menu text", contrast(scheme.onSurface, surface) >= 4.5f)
                assertTrue("${paper.id}: secondary text", contrast(scheme.onSurfaceVariant, surface) >= 4.5f)
                assertTrue("${paper.id}: actions", contrast(scheme.primary, surface) >= 4.5f)
            }
            assertTrue("${paper.id}: filled action", contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue("${paper.id}: primary container", contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5f)
            assertTrue("${paper.id}: selected action", contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5f)
        }
    }

    @Test fun speechHighlightRemainsQuietAndReadableOnEveryPaper() {
        for (paper in ReaderPaper.entries) {
            val colors = paper.colors ?: continue
            val mark = readerSpeechHighlight(colors.background, colors.text, colors.accent)
            val surface = mark.compositeOver(colors.background)
            assertTrue("${paper.id}: highlighted body", contrast(colors.text, surface) >= 7f)
            assertTrue("${paper.id}: visible but quiet", contrast(colors.background, surface) in 1.1f..2f)
            assertTrue("${paper.id}: lighter than text selection", mark.alpha < 0.3f)
        }
    }

    @Test fun customPaperHighlightDoesNotFurtherReduceAlreadyLowTextContrast() {
        val background = Color.White
        val text = Color(0xFF999999)
        val highlight = readerSpeechHighlight(background, text, Color(0xFF6650A4))
        assertTrue(contrast(text, highlight.compositeOver(background)) >= contrast(text, background) - 0.01f)
    }

    @Test fun imagePaperBackingProtectsCustomTextOverExtremePixels() {
        for (text in listOf(Color(0xFF262521), Color(0xFFD8DDD6), Color(0xFF777777), Color(0xFF999999),
            Color.Black, Color.White, Color.Red, Color.Green, Color.Blue)) {
            val highlight = readerSpeechHighlight(Color.White, text, Color(0xFF77562E), image = true)
            for (pixel in listOf(Color.Black, Color.White, Color(0xFF77562E))) {
                assertTrue("text=$text pixel=$pixel", contrast(text, highlight.compositeOver(pixel)) >= 4.5f)
            }
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
