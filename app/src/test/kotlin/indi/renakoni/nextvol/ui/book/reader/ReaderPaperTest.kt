package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}
