package indi.renakoni.nextvol.sourceexecution

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.di.WebDataSourceModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opt-in local sample audit. No source payload, credentials, URLs or script output is reported. */
@RunWith(AndroidJUnit4::class)
class PixivOriginalImportInstrumentedTest {
    @Test fun originalDefaultImportAndPanelsUseProductionWorkerWithoutWebsiteGrants() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pixivOriginal") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "pixiv-original-private.json")
        require(input.length() in 1..2_000_000)
        val bytes = input.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("9b5fde27e9a6f425a5067a52b2f8ec8082b258a27e968dd637a9d87df45b65d1", hash)
        val root = File(context.cacheDir, "pixiv-original-${UUID.randomUUID()}").apply { mkdirs() }
        val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val authority = hnovel.execution.ExecutionAuthority()
        val registry = WebSourceRegistry(authority)
        val accounts = SourceSessionManager(authority)
        val executor = AndroidIsolatedExecutor(context, authority)
        val sources = ImportedRuleSources(host, registry, authority, accounts, WebDataSourceModule.provideRuleTaskRunner(executor))
        try {
            // SourcesViewModel's text/file/url drafts default to AUTO_PROFILE.
            val preview = sources.importer.preview(bytes.toString(Charsets.UTF_8), AUTO_PROFILE)
            assertEquals(2, preview.candidates.size)
            assertEquals(1, preview.issues.size)
            assertEquals(ImportCode.UnsupportedType, preview.issues.single().code)
            val result = sources.importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
            assertNull(result.error)
            assertTrue(result.items.all { it.outcome == ImportOutcome.Added })
            var main: hnovel.content.RuleSource? = null
            var backup = 0
            for (item in result.items) {
                // Registration requires a grant. This unrelated origin grants no Pixiv access.
                val id = sources.activate(item.reference!!, listOf(NetworkGrant("https://pixiv-audit.invalid")))
                // Explicit host enablement, without altering the source's stored scripts/defaults.
                sources.setPreferences(id, enabled = true)
                val target = sources.loginTarget(id)
                assertTrue("Imported scripts must remain unchanged", preview.candidates.any {
                    it.importKey == target.rules.definition.importKey && it.rawJson == target.rules.definition.rawJson })
                val raw = Json.parseToJsonElement(target.rules.definition.rawJson).jsonObject
                val hasExplore = raw["exploreUrl"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                assertTrue(hasExplore)
                val form = SourceLoginService(sources, accounts).let { login ->
                    val panel = login.begin(id)
                    try { login.form(panel) } finally { login.cancel(panel) }
                }
                assertTrue(form.fields.isNotEmpty())
                if (target.rules.definition.enabled) {
                    assertEquals(EXTENSION_PROFILE, target.rules.definition.profile)
                    assertTrue(target.rules.definition.enabledExplore)
                    assertNull(main)
                    main = target.rules
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\noriginal-main: import, activation, panel passed (${form.fields.size} fields); no authenticated reading tested\n") })
                } else {
                    assertFalse(target.rules.definition.enabledExplore)
                    assertFalse(sources.installedSources().single { ImportedRuleSources.id(it.definition) == id }.preferences.discoveryVisible)
                    backup++
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\noriginal-backup: import, explicit enablement, panel passed (${form.fields.size} fields); exploreUrl present but discovery disabled by default\n") })
                }
            }
            assertNotNull(main); assertEquals(1, backup)
            val catalog = main!!.openDiscovery("original-audit").catalog()
            assertTrue(catalog.rows.isNotEmpty())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\noriginal-main: unmodified catalogue passed (${catalog.rows.size} rows); entries are not evidence of readable works\n") })
        } finally { sources.stop(); executor.close(); root.deleteRecursively() }
    }
}
