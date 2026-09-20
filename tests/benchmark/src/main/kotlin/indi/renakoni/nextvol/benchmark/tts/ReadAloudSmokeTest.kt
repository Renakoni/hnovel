package indi.renakoni.nextvol.benchmark.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadAloudSmokeTest : UiAutomatorTest() {
    @Test fun readerStartsThePrivateMediaServiceAndMissingEngineCanBeRetriedAndStopped() {
        val engines = shell("cmd package query-services --brief -a android.intent.action.TTS_SERVICE")
        configureEngine("invalid.nextvol.speech.engine")
        launchApp()
        assertFalse(shell("dumpsys activity services $TARGET_PACKAGE").contains("ReadAloudService"))
        openBottomNavigation("Bookshelf")
        clickText("Benchmark Sample Novel")
        clickScrolledText("Benchmark Chapter One")
        assertTextContains("Benchmark paragraph")
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        clickDescription("Listen to this book")
        assertText("Unable to continue")
        assertText(if (engines.contains("No services found")) {
            "No speech engine was found. Install and configure an engine in system settings, then retry."
        } else "The selected speech engine is unavailable. Check the engine in settings.")
        clickDescription("Retry")
        assertText("Unable to continue")
        clickDescription("Read aloud settings")
        assertDescription("Refresh engines and voices")
        clickScrolledText("System speech settings")
        assertTrue("System speech settings did not open", device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), TIMEOUT))
        device.pressBack()
        assertDescription("Refresh engines and voices")
        clickDescription("Stop")
        assertTrue(device.wait(Until.gone(By.text("Unable to continue")), TIMEOUT))
        assertServiceStopped()
    }

    @Test fun realEnginePreviewFinishesWithoutKeepingTheServiceInForeground() {
        val engine = InstrumentationRegistry.getArguments().getString("speechEngine")
        assumeTrue("Supply speechEngine for the separate real-engine acceptance run", !engine.isNullOrEmpty())
        configureEngine(engine!!)
        launchApp()
        shell("am start -W -n $TARGET_PACKAGE/.MainActivity -a indi.renakoni.nextvol.OPEN_READ_ALOUD")
        clickCenter(scrollToText("Preview voice"))
        assertText("Finished")
        val service = shell("dumpsys activity services $TARGET_PACKAGE/.tts.ReadAloudService")
        assertFalse(service, service.contains("isForeground=true"))
        clickDescription("Stop")
        assertTrue(device.wait(Until.gone(By.text("Finished")), TIMEOUT))
        assertServiceStopped()
    }

    @Test fun importedVoiceCanBeSelectedPlayedAndRemovedWithoutASystemEngine() {
        val result = shell("am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
            "-a $TARGET_PACKAGE.benchmark.SPEECH_SOURCE")
        assertTrue(result, result.contains("speech-source=SUCCEEDED"))
        configureEngine("invalid.nextvol.speech.engine")
        launchApp()
        shell("am start -W -n $TARGET_PACKAGE/.MainActivity -a indi.renakoni.nextvol.OPEN_READ_ALOUD")
        clickScrolledText("Voices")
        clickScrolledText("Imported test voice")
        device.pressBack()
        assertText("Imported test voice")
        clickScrolledText("Preview voice")
        assertText("Finished")
        clickDescription("Stop")
        assertServiceStopped()
        clickScrolledText("Voices")
        scrollToText("Imported test voice")
        clickDescription("Voice options")
        clickText("Remove voice")
        assertTrue(device.wait(Until.gone(By.text("Imported test voice")), TIMEOUT))
        scrollToText("System speech")
        assertText("System speech")
    }

    private fun assertServiceStopped() {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var service: String
        do {
            service = shell("dumpsys activity services $TARGET_PACKAGE/.tts.ReadAloudService")
            if (!service.contains("ServiceRecord{")) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertFalse(service, service.contains("ServiceRecord{"))
    }

    private fun configureEngine(engine: String) {
        val result = shell("am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
            "-a $TARGET_PACKAGE.benchmark.SPEECH_ENGINE --es engine $engine")
        assertTrue(result, result.contains("speech=SUCCEEDED"))
    }
}
