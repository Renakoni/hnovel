package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.dmzz_yyhyy.lightnovelreader.LightNovelReaderApplication
import indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.BrowserTestHostActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: production importer, account store and isolated rule worker, with manual website login. */
@RunWith(AndroidJUnit4::class)
class HlibBrowserLiveInstrumentedTest {
    @Test fun installedSourceUsesItsOwnNativeSession(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveHlib") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as LightNovelReaderApplication
        val sources = application.importedRuleSources
        sources.restore()
        val raw = instrumentation.context.assets.open("hlib-native.json").bufferedReader().use { it.readText() }
        val preview = sources.importer.preview(raw)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        val candidate = preview.candidates.single()
        val existing = candidate.existing
        val reference = if (existing != null) {
            val definition = sources.definitions.list().single { it.reference() == existing }
            // A test must not replace an unrelated/user-edited definition behind the UI.
            assertEquals(true, Json.parseToJsonElement(definition.rawJson).jsonObject["browserRead"]?.jsonPrimitive?.boolean)
            existing
        } else {
            val saved = sources.importer.commit(preview, listOf(ImportSelection(candidate.index, ImportDecision.Add)))
            assertNull(saved.error)
            saved.items.single().reference!!
        }
        val id = sources.activate(reference, listOf(NetworkGrant("https://hlib.cc")))
        sources.setPreferences(id, enabled = true)
        val target = sources.loginTarget(id)
        fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("hlibStage", value) })
        report("installed browserRead=true generation=${target.generation}")
        if (args.getString("hlibAction") == "login") {
            ActivityScenario.launch(BrowserTestHostActivity::class.java).use {
                target.rules.loginForm()
                target.rules.login(emptyMap())
            }
            report("website window completed; authentication still requires a successful protected read")
            return@runBlocking
        }
        if (args.getString("hlibAction") == "install") return@runBlocking
        val found = target.rules.search("女性干员X男性博士", 1)
        assertTrue(found.isNotEmpty())
        report("search count=${found.size}")
        val url = "https://hlib.cc/s/sKgIeL?p=1"
        val book = target.rules.information(url)
        assertTrue(book.title.isNotBlank())
        val chapters = target.rules.directory(url).filterNot { it.isVolume }
        assertTrue(chapters.size >= 63)
        report("directory count=${chapters.size}")
        val content = target.rules.content(url, chapters.first().id)
        val characters = content.parts.sumOf { it.text?.length ?: 0 }
        assertTrue(characters > 10000)
        report("first chapter parts=${content.parts.size} characters=$characters")
    }
}
