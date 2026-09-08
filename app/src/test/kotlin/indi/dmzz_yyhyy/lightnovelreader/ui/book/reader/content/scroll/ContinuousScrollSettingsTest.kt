package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import io.mockk.coEvery
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.userdata.BooleanUserData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousScrollSettingsTest {
    @Test
    fun observationAndRequestTimeReadKeepTheSameHandleAndTrueDefault() = runTest {
        val flow = MutableStateFlow(true)
        val handle = mockk<BooleanUserData>()
        every { handle.getFlowWithDefault(true) } returns flow
        coEvery { handle.getOrDefault(true) } returnsMany listOf(false, true)
        val settings: ContinuousScrollSettings = UserDataContinuousScrollSettings(handle)
        assertSame(flow, settings.getFlow())
        assertFalse(settings.isEnabled())
        assertTrue(settings.isEnabled())
        coVerifySequence {
            handle.getFlowWithDefault(true)
            handle.getOrDefault(true)
            handle.getOrDefault(true)
        }
    }
}
