package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.ui.unit.IntSize
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ReaderLayoutPolicyTest {
    @Test
    fun autoUsesTheOuterWindowThresholdWithoutOscillating() {
        for ((width, columns) in listOf(839 to 1, 840 to 2, 841 to 2, 839 to 1)) {
            val input = normalText(width)
            val expected = resolveReaderLayout(input)
            assertEquals(columns, expected.geometry!!.columns)
            repeat(20) { assertEquals(expected, resolveReaderLayout(input)) }
        }
    }

    @Test
    fun forcedDoubleBypassesAutoWidthButNotTheMinimumLeafWidth() {
        assertEquals(1, geometry(normalText(720)).columns)
        val preferred = normalText(720).copy(preference = "double")
        assertEquals(2, geometry(preferred).columns)
        assertEquals(332, geometry(preferred).leafSize.width)
        val narrow = resolveReaderLayout(preferred.copy(hostSize = IntSize(680, 800)))
        assertEquals(1, narrow.geometry!!.columns)
        assertEquals(ReaderLayoutReason.WindowTooNarrow, narrow.reason)
        assertEquals("double", preferred.preference)
        assertEquals(2, geometry(preferred).columns)
    }

    @Test
    fun bothAutoAndDoubleRequireTheMinimumHostHeight() {
        for (preference in listOf("auto", "double")) {
            val short = tablet.copy(hostSize = IntSize(1280, 479), preference = preference)
            assertEquals(ReaderLayoutReason.WindowTooShort, resolveReaderLayout(short).reason)
            assertEquals(1, geometry(short).columns)
            assertEquals(2, geometry(short.copy(hostSize = IntSize(1280, 480))).columns)
        }
    }

    @Test
    fun bodyMustFitEightResolvedLineHeightsAfterPadding() {
        val input = tablet.copy(
            hostSize = IntSize(1280, 480),
            padding = ReaderPaddingPx(24, 24, 100, 100), lineHeightPx = 40f,
        )
        assertEquals(280, geometry(input).leafSize.height)
        assertEquals(ReaderLayoutReason.WindowTooShort, resolveReaderLayout(input).reason)
        val exact = input.copy(padding = input.padding.copy(bottomPx = 60))
        assertEquals(2, geometry(exact).columns)
        assertEquals(1, geometry(exact.copy(padding = exact.padding.copy(bottomPx = 61))).columns)
        assertEquals(1, geometry(exact.copy(lineHeightPx = 40.125f)).columns)
    }

    @Test
    fun resolvedFontScalingCanFallBackAndRecoverWithoutChangingPreference() {
        for (preference in listOf("auto", "double")) {
            for ((scale, columns) in listOf(1f to 2, 1.3f to 2, 2f to 1, 1f to 2)) {
                val input = tablet.copy(
                    preference = preference, fontSizePx = 18f * scale, lineHeightPx = 26f * scale,
                )
                val result = resolveReaderLayout(input)
                assertEquals(columns, result.geometry!!.columns)
                if (columns == 2) {
                    assertEquals(604, result.geometry.leafSize.width)
                    assertNull(result.reason)
                } else {
                    assertEquals(ReaderLayoutReason.TextTooLarge, result.reason)
                }
                assertEquals(preference, input.preference)
            }
        }
    }

    @Test
    fun resolvedFontPixelsAreNotRoundedDownAtTheSafetyBoundary() {
        val input = tablet.copy(fontSizePx = 30.2f)
        assertEquals(2, geometry(input).columns)
        assertEquals(ReaderLayoutReason.TextTooLarge,
            resolveReaderLayout(input.copy(fontSizePx = 30.21f)).reason)
    }

    @Test
    fun orientationDoesNotOverrideActualWindowSpace() {
        assertEquals(2, geometry(normalText(1000).copy(hostSize = IntSize(1000, 1600))).columns)
        assertEquals(1, geometry(normalText(1000).copy(hostSize = IntSize(1000, 450))).columns)
    }

    @Test
    fun explicitSingleDoesNotRunDoublePageSafetyChecks() {
        val result = resolveReaderLayout(tablet.copy(preference = "single", supportsDoublePage = false))
        assertEquals(1, result.geometry!!.columns)
        assertEquals(1232, result.geometry.leafSize.width)
        assertNull(result.reason)
    }

    @Test
    fun unknownPreferenceUsesAutoRatherThanForcingDouble() {
        for (width in listOf(720, 840)) {
            for (unknown in listOf("", "future-layout")) {
                assertEquals(resolveReaderLayout(normalText(width)),
                    resolveReaderLayout(normalText(width).copy(preference = unknown)))
            }
        }
    }

    @Test
    fun unsupportedContentFallsBackForTheWholeChapterAndCanRecover() {
        val input = tablet.copy(preference = "double", supportsDoublePage = false)
        val result = resolveReaderLayout(input)
        assertEquals(1, result.geometry!!.columns)
        assertEquals(ReaderLayoutReason.UnsupportedContent, result.reason)
        assertEquals("double", input.preference)
        assertEquals(2, geometry(input.copy(supportsDoublePage = true)).columns)
    }

    @Test
    fun contentMustExplicitlyOptInToDoublePageCompatibility() {
        val input = ReaderLayoutInput(
            hostSize = tablet.hostSize, density = tablet.density, padding = tablet.padding,
            fontSizePx = tablet.fontSizePx, lineHeightPx = tablet.lineHeightPx, mode = ReaderMode.Flip,
        )
        assertEquals(ReaderLayoutReason.UnsupportedContent, resolveReaderLayout(input).reason)
    }

    @Test
    fun scrollIsOneCenteredColumnRegardlessOfPaginationPreference() {
        for (preference in listOf("auto", "single", "double", "unknown")) {
            val input = tablet.copy(
                mode = ReaderMode.Scroll, preference = preference, supportsDoublePage = false,
            )
            val result = resolveReaderLayout(input)
            assertEquals(ReaderBodyGeometry(280, 0, IntSize(720, 800)), result.geometry)
            assertEquals(ReaderLayoutReason.ScrollMode, result.reason)
            assertEquals(preference, input.preference)
        }
    }

    @Test
    fun scrollNeverExceedsAvailableWidthAndCentersOddPixelsTowardEnd() {
        val narrow = normalText(500).copy(mode = ReaderMode.Scroll)
        assertEquals(ReaderBodyGeometry(16, 0, IntSize(468, 800)), geometry(narrow))
        val wide = narrow.copy(hostSize = IntSize(1281, 800))
        assertEquals(280, geometry(wide).startPx)
        assertEquals(720, geometry(wide).leafSize.width)
        assertEquals(281, wide.hostSize.width - geometry(wide).startPx - geometry(wide).widthPx)
    }

    @Test
    fun asymmetricResolvedInsetsAndIndicatorPaddingAreAppliedOnlyOnce() {
        val input = tablet.copy(padding = ReaderPaddingPx(24, 40, 32, 48))
        val body = geometry(input)
        assertEquals(ReaderBodyGeometry(24, 32, IntSize(596, 720), 2, 24), body)
        assertEquals(input.hostSize.width, body.startPx + body.widthPx + input.padding.endPx)
        assertEquals(input.hostSize.height, body.topPx + body.leafSize.height + input.padding.bottomPx)
    }

    @Test
    fun oddPixelRemainderBelongsToTheGutterNotEitherLeaf() {
        val body = geometry(tablet.copy(hostSize = IntSize(1281, 800)))
        assertEquals(604, body.leafSize.width)
        assertEquals(25, body.gutterPx)
        assertEquals(1233, body.widthPx)
    }

    @Test
    fun fractionalDensityUsesResolvedPixelInputsAndOneRoundedGutter() {
        val input = tablet.copy(
            hostSize = IntSize(1665, 1040), density = 1.3f,
            padding = ReaderPaddingPx(31, 52, 42, 62),
            fontSizePx = 23.4f, lineHeightPx = 33.8f,
        )
        assertEquals(ReaderBodyGeometry(31, 42, IntSize(775, 936), 2, 32), geometry(input))
        assertEquals(ReaderBodyGeometry(354, 42, IntSize(936, 936)),
            geometry(input.copy(mode = ReaderMode.Scroll)))
    }

    @Test
    fun autoThresholdIsDensityIndependent() {
        for (density in listOf(0.75f, 1f, 1.25f, 2f, 2.625f)) {
            for ((width, columns) in listOf(839 to 1, 840 to 2, 841 to 2)) {
                val padding = (16 * density).roundToInt()
                val input = normalText(width).copy(
                    hostSize = IntSize((width * density).roundToInt(), (800 * density).roundToInt()),
                    density = density, padding = ReaderPaddingPx(padding, padding),
                    fontSizePx = 16 * density, lineHeightPx = 24 * density,
                )
                assertEquals(columns, geometry(input).columns)
                assertTrue(geometry(input).leafSize.width > 0)
            }
        }
    }

    @Test
    fun zeroOrPaddingConsumedSizeWaitsEvenForScrollOrExplicitSingle() {
        for (mode in ReaderMode.entries) for (preference in listOf("auto", "single", "double")) {
            for (size in listOf(IntSize.Zero, IntSize(0, 800), IntSize(1280, 0), IntSize(48, 800), IntSize(40, 800))) {
                val input = tablet.copy(hostSize = size, mode = mode, preference = preference)
                assertNull(resolveReaderLayout(input).geometry)
                assertEquals(ReaderLayoutReason.AwaitingMeasurement, resolveReaderLayout(input).reason)
            }
            val consumedHeight = tablet.copy(
                mode = mode, preference = preference, padding = ReaderPaddingPx(24, 24, 400, 401),
            )
            assertNull(resolveReaderLayout(consumedHeight).geometry)
        }
        assertEquals(2, geometry(tablet).columns)
    }

    @Test
    fun geometryIdentityIncludesOffsetsAndGutterEvenWhenLeafSizeIsUnchanged() {
        val original = geometry(tablet)
        val oddWidth = geometry(tablet.copy(hostSize = IntSize(1281, 800)))
        val shifted = geometry(tablet.copy(padding = ReaderPaddingPx(16, 32)))
        assertEquals(original.leafSize, oddWidth.leafSize)
        assertEquals(original.leafSize, shifted.leafSize)
        assertNotEquals(original, oddWidth)
        assertNotEquals(original, shifted)
    }

    private val tablet = ReaderLayoutInput(
        hostSize = IntSize(1280, 800), density = 1f, padding = ReaderPaddingPx(24, 24),
        fontSizePx = 18f, lineHeightPx = 26f, mode = ReaderMode.Flip, supportsDoublePage = true,
    )

    private fun normalText(width: Int) = tablet.copy(
        hostSize = IntSize(width, 800), padding = ReaderPaddingPx(16, 16),
        fontSizePx = 16f, lineHeightPx = 24f,
    )

    private fun geometry(input: ReaderLayoutInput) = checkNotNull(resolveReaderLayout(input).geometry)
}
