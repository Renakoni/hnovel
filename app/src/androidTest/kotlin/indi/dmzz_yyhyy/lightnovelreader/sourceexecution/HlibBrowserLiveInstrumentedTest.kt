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
import kotlinx.coroutines.flow.first
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
            if (args.getString("hlibAction") == "describe") {
                instrumentation.sendStatus(0, Bundle().apply { putString("hlibDigest", definition.contentDigest) })
                return@runBlocking
            }
            if (args.getString("hlibAction") == "update") {
                // An explicit expected revision prevents overwriting a user's later source edits.
                assertEquals(args.getString("hlibExpectedDigest"), definition.contentDigest)
                val saved = sources.importer.commit(preview, listOf(ImportSelection(candidate.index, ImportDecision.Replace(existing))))
                assertNull(saved.error)
                val replacement = saved.items.single().reference!!
                val updates = dagger.hilt.android.EntryPointAccessors.fromApplication(application,
                    indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.SourceVerificationDebugEntryPoint::class.java).revisions()
                updates.apply(indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources.id(definition),
                    replacement, listOf(NetworkGrant("https://hlib.cc")))
                replacement
            } else existing
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
        if (args.getString("hlibAction") in listOf("install", "update")) return@runBlocking
        if (args.getString("hlibAction") == "recover") {
            val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(application,
                indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.SourceVerificationDebugEntryPoint::class.java)
            val runtime = (entry.registry().resolve(id) as indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution.Ready).runtime
            indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.VerificationTestHostActivity.coordinator = entry.coordinator()
            try {
                ActivityScenario.launch(indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.VerificationTestHostActivity::class.java).use {
                    kotlinx.coroutines.withContext(indi.dmzz_yyhyy.lightnovelreader.data.web.ForegroundSourceRequest()) {
                        val search = kotlinx.coroutines.withTimeout(600000) {
                            runtime.search.search(runtime.search.searchTypes.first(), "女性干员X男性博士").first()
                        }
                        assertTrue(search is io.nightfish.lightnovelreader.api.web.search.SearchResult.MultipleBook)
                        report("production runtime search resumed; pending=${entry.coordinator().prompts.value.size}")
                    }
                }
            } finally { indi.dmzz_yyhyy.lightnovelreader.sourcebrowser.VerificationTestHostActivity.coordinator = null }
        }
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
