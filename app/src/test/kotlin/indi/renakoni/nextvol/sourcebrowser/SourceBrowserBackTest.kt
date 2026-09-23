package indi.renakoni.nextvol.sourcebrowser

import android.app.Activity
import android.app.Application
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import hnovel.network.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper.idleMainLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 27, 35], application = Application::class)
class SourceBrowserBackTest {
    @After fun clearActiveBrowsers() {
        SourceBrowserService.active = null
        NativeSourceBrowserService.active = null
        unmockkObject(BrowserWire)
    }

    @Test fun nativeBackNavigatesHistoryBeforeCancelling() {
        val service = nativeService()
        val view = service.webView!!
        shadowOf(view).setCanGoBack(true)
        val controller = Robolectric.buildActivity(NativeSourceBrowserActivity::class.java).setup()
        back(controller.get())
        assertEquals(1, shadowOf(view).goBackInvocations)
        assertFalse(controller.get().isFinishing)
        verify(exactly = 0) { service.cancel(); service.confirm() }
        shadowOf(view).setCanGoBack(false)
        back(controller.get())
        verify(exactly = 1) { service.cancel() }
        controller.pause().stop().destroy()
    }

    @Test fun loginBackNavigatesHistoryThenCompletesOnceEvenAfterDestruction() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results)
        val view = service.webView!!
        shadowOf(view).setCanGoBack(true)
        val controller = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        back(controller.get())
        assertEquals(1, shadowOf(view).goBackInvocations)
        assertNull(shadowOf(view).lastEvaluatedJavascript)
        assertTrue(results.isEmpty())
        assertFalse(controller.get().isFinishing)
        shadowOf(view).setCanGoBack(false)
        back(controller.get())
        shadowOf(view).lastEvaluatedJavascriptCallback.onReceiveValue(JsonPrimitive("""{"value":"signed in"}""").toString())
        idleMainLooper()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
        service.fail()
        idleMainLooper()
        assertEquals(1, results.size)
        assertEquals("signed in", (results.single() as BrokerResult.Success).response.text())
    }

    @Test fun verificationBackCancelsEvenWithHistoryAndCompletesOnce() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results, verification = true)
        val view = service.webView!!
        shadowOf(view).setCanGoBack(true)
        val controller = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        back(controller.get())
        controller.pause().stop().destroy()
        service.fail()
        idleMainLooper()
        assertEquals(0, shadowOf(view).goBackInvocations)
        assertNull(shadowOf(view).lastEvaluatedJavascript)
        assertEquals(1, results.size)
        assertTrue(results.single() is BrokerResult.Failure)
    }

    @Test fun recreationKeepsLoginPendingAndReattachesItsWebView() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results)
        val controller = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        controller.recreate()
        assertSame(controller.get(), service.activity)
        assertNotNull(service.webView!!.parent)
        assertTrue(results.isEmpty())
        controller.get().finish()
        controller.pause().stop().destroy()
        idleMainLooper()
        assertEquals(1, results.size)
        assertTrue(results.single() is BrokerResult.Failure)
    }

    @Test fun obsoleteLoginWindowCannotCancelTheCurrentWindow() {
        val results = mutableListOf<BrokerResult>()
        val service = sourceService(results)
        val old = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        old.pause().stop()
        val current = Robolectric.buildActivity(SourceBrowserActivity::class.java).setup()
        old.get().finish()
        old.destroy()
        idleMainLooper()
        assertSame(current.get(), service.activity)
        assertFalse(current.get().isFinishing)
        assertTrue(results.isEmpty())
        current.get().finish()
        current.pause().stop().destroy()
        idleMainLooper()
        assertEquals(1, results.size)
    }

    @Test fun nativeRecreationDoesNotCancelAndFinishingReleasesItsSession() {
        val service = nativeService()
        val controller = Robolectric.buildActivity(NativeSourceBrowserActivity::class.java).setup()
        controller.recreate()
        assertSame(controller.get(), service.activity)
        verify(exactly = 0) { service.cancel() }
        controller.get().finish()
        controller.pause().stop().destroy()
        assertNull(service.activity)
        verify(exactly = 1) { service.cancel() }
    }

    private fun back(activity: Activity) {
        idleMainLooper()
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idleMainLooper()
    }

    private fun nativeService(): NativeSourceBrowserService {
        val service = mockk<NativeSourceBrowserService>(relaxed = true)
        val view = WebView(RuntimeEnvironment.getApplication())
        var owner: NativeSourceBrowserActivity? = null
        every { service.webView } returns view
        every { service.title } returns "Website"
        every { service.activity } answers { owner }
        every { service.activity = any() } answers { owner = firstArg() }
        NativeSourceBrowserService.active = service
        return service
    }

    private fun sourceService(results: MutableList<BrokerResult>, verification: Boolean = false): SourceBrowserService {
        // Attach the real service without starting its disposable Chromium process.
        val service = Robolectric.buildService(SourceBrowserService::class.java).get()
        val request = BrokerRequest("login", "https://source.invalid/")
        ReflectionHelpers.setField(service, "job", BrowserJob(request, BrowserOptions(interactive = true, verificationCode = verification)))
        ReflectionHelpers.setField(service, "mainUrl", request.url)
        ReflectionHelpers.setField(service, "mainResponse", BrokerResponse(200, request.url, emptyMap(), byteArrayOf(), "UTF-8", 0))
        // Robolectric's pipe is not a blocking OS pipe; Binder transport is covered on device.
        mockkObject(BrowserWire)
        val output = mockk<ParcelFileDescriptor>(relaxed = true)
        var payload = ""
        every { BrowserWire.pipe(any(), any()) } answers { payload = firstArg(); output }
        val host = mockk<IBrowserHost>(relaxed = true)
        every { host.complete(output) } answers {
            results += Json.decodeFromString<BrokerResult>(payload)
        }
        ReflectionHelpers.setField(service, "host", host)
        service.webView = WebView(service)
        SourceBrowserService.active = service
        return service
    }
}
