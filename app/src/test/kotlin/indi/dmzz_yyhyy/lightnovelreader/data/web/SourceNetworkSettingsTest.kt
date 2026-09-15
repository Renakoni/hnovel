package indi.dmzz_yyhyy.lightnovelreader.data.web

import android.app.Application
import android.content.ContextWrapper
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.*
import hnovel.content.RuleSourceFixture
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.network.NetworkGrant
import hnovel.network.SourceNetworkMode
import indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceNetworkSettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun host() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = folder.newFolder()
        override fun getFilesDir() = root
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    @Test fun stableIdentityPreferencesSurviveRestartAndStayOutOfOtherSources() {
        val context = host()
        AndroidSourceNetworks(context).use { networks ->
            val first = SourceNetworkSettings(context, networks)
            val id = Identifier("rule", "a")
            val other = Identifier("plugin", "a")
            assertEquals(SourceNetworkMode.SystemDefault, first.mode(id))
            first.setBypassVpn(id, true)
            assertEquals(SourceNetworkMode.SystemDefault, first.mode(other))
            val restored = SourceNetworkSettings(context, networks)
            assertEquals(SourceNetworkMode.BypassVpn, restored.mode(id))
            val diagnostic = restored.snapshotFor(id)
            restored.setBypassVpn(id, false)
            assertEquals(SourceNetworkMode.BypassVpn, diagnostic.snapshot().mode)
            assertEquals(SourceNetworkMode.SystemDefault, restored.forSource(id).snapshot().mode)
            assertEquals(SourceNetworkMode.SystemDefault, SourceNetworkSettings(context, networks).mode(id))
        }
    }

    @Test fun failedPersistenceKeepsThePreviousModeInMemoryAndOnDisk() {
        val context = host()
        AndroidSourceNetworks(context).use { networks ->
            val settings = SourceNetworkSettings(context, networks)
            val id = Identifier("fixture", "a")
            settings.setBypassVpn(id, true)
            val root = context.filesDir
            val saved = File(root.parentFile, "saved-preferences")
            check(root.renameTo(saved))
            root.writeText("parent path is unavailable")
            try {
                assertTrue(runCatching { settings.setBypassVpn(id, false) }.isFailure)
                assertEquals(SourceNetworkMode.BypassVpn, settings.mode(id))
            } finally { check(root.delete()); check(saved.renameTo(root)) }
            assertEquals(SourceNetworkMode.BypassVpn, SourceNetworkSettings(context, networks).mode(id))
        }
    }

    @Test fun togglingDoesNotReplaceRuntimeOrRotateAccountAndRemovalClearsMode() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            AndroidSourceNetworks(context).use { networks ->
                val settings = SourceNetworkSettings(context, networks)
                val registry = WebSourceRegistry(fixture.authority)
                val accounts = SourceSessionManager(fixture.authority)
                val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, networkSettings = settings)
                try {
                    val preview = sources.importer.preview(fixture.raw().toString())
                    val reference = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                    val id = sources.activate(reference, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                    val listing = registry.sources.value.single()
                    val account = accounts.current(id)
                    settings.setBypassVpn(id, true)
                    assertSame(listing, registry.sources.value.single())
                    assertEquals(account, accounts.current(id))
                    sources.setPreferences(id, enabled = false)
                    assertEquals(SourceNetworkMode.BypassVpn, settings.mode(id))
                    sources.setPreferences(id, enabled = true)
                    assertEquals(SourceNetworkMode.BypassVpn, settings.mode(id))
                    assertEquals(0, fixture.server.requestCount)
                    sources.remove(id)
                    assertEquals(SourceNetworkMode.SystemDefault, settings.mode(id))
                } finally { sources.stop() }
            }
        }
    }

    @Test fun nonVpnCapabilityIsRequiredAndValidatedWifiWinsOverAvailableCellular() {
        fun network(transport: Int) = NetworkCapabilities().apply {
            shadowOf(this).addCapability(NET_CAPABILITY_INTERNET)
            shadowOf(this).addCapability(NET_CAPABILITY_NOT_VPN)
            shadowOf(this).addTransportType(transport)
        }
        val wifi = network(TRANSPORT_WIFI).apply { shadowOf(this).addCapability(NET_CAPABILITY_VALIDATED) }
        val cellular = network(TRANSPORT_CELLULAR)
        assertTrue(AndroidSourceNetworks.eligible(wifi))
        assertTrue(AndroidSourceNetworks.rank(wifi) < AndroidSourceNetworks.rank(cellular))
        assertFalse(AndroidSourceNetworks.eligible(NetworkCapabilities(wifi).apply { shadowOf(this).removeCapability(NET_CAPABILITY_NOT_VPN) }))
        assertFalse(AndroidSourceNetworks.eligible(NetworkCapabilities(wifi).apply { shadowOf(this).addTransportType(TRANSPORT_VPN) }))
    }
}
