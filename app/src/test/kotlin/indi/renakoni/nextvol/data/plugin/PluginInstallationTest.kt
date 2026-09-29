package indi.renakoni.nextvol.data.plugin

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import dalvik.system.PathClassLoader
import indi.renakoni.nextvol.data.plugin.injector.PluginInjector
import indi.renakoni.nextvol.data.plugin.install.InstallState
import indi.renakoni.nextvol.data.plugin.install.PluginInstallError
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.WebBookDataSourceManager
import indi.renakoni.nextvol.utils.ApkSignatureInfo
import indi.renakoni.nextvol.utils.classLoader
import indi.renakoni.nextvol.utils.getApkSignatures
import io.mockk.*
import io.nightfish.lightnovelreader.api.ApiMetadata
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginInstallationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packages = mockk<PackageManager>()
    private val sources = mockk<WebBookDataSourceManager>(relaxed = true)
    private val injector = mockk<PluginInjector>(relaxed = true)
    private val users = mockk<UserDataRepository>(relaxed = true)
    private val loader = mockk<PathClassLoader>()
    private lateinit var manager: PluginManager
    private lateinit var directory: File
    private var entryClass: Class<*> = SupportedPlugin::class.java
    private var archiveReadable = true
    private var metadataReadable = true
    private val signatures = mutableMapOf<String, List<ApkSignatureInfo>?>()
    private val stages = listOf(
        InstallState.Start.ParsePackageInfo, InstallState.Start.Clean,
        InstallState.Start.ParsePluginMetadata, InstallState.Start.CheckPluginInstallLegality,
        InstallState.Start.WritePluginMetadataToFile, InstallState.Start.CopyPlugin
    )

    @Before fun setup() {
        val context = mockk<Context> {
            every { dataDir } returns temporary.newFolder("app")
            every { cacheDir } returns temporary.newFolder("cache")
            every { packageManager } returns packages
        }
        every { packages.getPackageArchiveInfo(any(), any<Int>()) } answers {
            if (!archiveReadable) null else PackageInfo().apply {
                packageName = PACKAGE
                applicationInfo = ApplicationInfo().apply {
                    metaData = Bundle().apply {
                        if (metadataReadable) putString("lnr_plugin", entryClass.name)
                    }
                }
            }
        }
        mockkStatic("indi.renakoni.nextvol.utils.ClassLoaderKt", "indi.renakoni.nextvol.utils.SignatureKt")
        every { classLoader(any(), any(), any()) } returns loader
        every { loader.loadClass(any()) } answers { entryClass }
        every { getApkSignatures(any()) } answers { signatures[firstArg<File>().absolutePath] }
        coEvery { users.stringListUserData(any()).getOrDefault(any()) } returns emptyList()
        manager = PluginManager(context, sources, injector, users, mockk(relaxed = true))
        directory = manager.getPluginDir(PACKAGE)
    }

    @After fun cleanup() {
        unmockkStatic("indi.renakoni.nextvol.utils.ClassLoaderKt", "indi.renakoni.nextvol.utils.SignatureKt")
        temporary.root.walkTopDown().forEach { it.setWritable(true) }
    }

    @Test fun firstInstallWritesArtifactsInOrderWithoutActivatingPlugin() = runBlocking {
        val apk = apk(mapOf("assets/nested/message.txt" to "hello"))
        val states = install(apk)
        assertCompleted(states)
        assertArrayEquals(apk.readBytes(), directory.resolve("plugin").readBytes())
        assertEquals("hello", directory.resolve("asset/nested/message.txt").readText())
        assertEquals(metadata(), manager.allPluginList.single())
        assertFalse(directory.resolve("lock").exists())
        assertTrue(manager.loadedPluginMap.isEmpty())
        verify(exactly = 0) { injector.providePlugin(any(), any()) }
        verify(exactly = 0) { sources.loadWebDataSourcesFromClassLoader(any(), any(), any(), any()) }
    }

    @Test fun replacementPreservesDataAndRemovesOnlyOldInstallationArtifacts() = runBlocking {
        assertCompleted(install(apk(mapOf("assets/old.txt" to "old"))))
        val data = directory.resolve("data/nested/user.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        directory.resolve("error").writeText("old error")
        val sibling = directory.parentFile.resolve("another-plugin/keep").apply { parentFile!!.mkdirs(); writeText("neighbor") }
        assertCompleted(install(apk(mapOf("assets/new.txt" to "new"))))
        assertEquals("keep", data.readText())
        assertEquals("neighbor", sibling.readText())
        assertFalse(directory.resolve("asset/old.txt").exists())
        assertFalse(directory.resolve("error").exists())
        assertEquals(1, manager.allPluginList.size)
    }

    @Test fun invalidArchiveStopsBeforeCleanup() = runBlocking {
        directory.mkdirs()
        directory.resolve("lock").writeText("")
        archiveReadable = false
        val states = install(temporary.newFile().apply { writeText("not an APK") })
        assertFailed(states, 1)
        assertTrue(directory.resolve("lock").exists())
        verify(exactly = 0) { loader.loadClass(any()) }
    }

    @Test fun missingManifestMetadataStopsBeforeLegalityAndWrites() = runBlocking {
        metadataReadable = false
        assertFailed(install(apk()), 3)
        assertFalse(directory.exists())
    }

    @Test fun missingEntryClassStopsBeforeLegalityAndWrites() = runBlocking {
        every { loader.loadClass(any()) } throws ClassNotFoundException("fixture")
        assertFailed(install(apk()), 3, ClassNotFoundException::class.java)
        assertFalse(directory.exists())
    }

    @Test fun missingAnnotationStopsBeforeLegalityAndWrites() = runBlocking {
        entryClass = String::class.java
        assertFailed(install(apk()), 3)
        assertFalse(directory.exists())
    }

    @Test fun firstInstallCurrentlyAcceptsUnsupportedApi() = runBlocking {
        entryClass = UnsupportedPlugin::class.java
        assertCompleted(install(apk()))
        assertEquals(Int.MAX_VALUE, manager.allPluginList.single().apiVersion)
    }

    @Test fun firstInstallCurrentlyAcceptsDiscoveredAppPlugin() = runBlocking {
        discoveredAppPlugin()
        assertCompleted(install(apk()))
    }

    @Test fun replacementRejectsDiscoveredAppPluginBeforeApiCheck() = runBlocking {
        assertCompleted(install(apk()))
        discoveredAppPlugin()
        entryClass = UnsupportedPlugin::class.java
        assertFailed(install(apk()), 4, PluginInstallError.AppPluginExist::class.java)
        assertEquals(ApiMetadata.API_VERSION, manager.allPluginList.single().apiVersion)
    }

    @Test fun failedReplacementKeepsPublishedMetadataButLeavesPartialFilesLocked() = runBlocking {
        assertCompleted(install(apk()))
        val old = manager.allPluginList.single()
        entryClass = UpdatedPlugin::class.java
        assertFailed(install(apk(linkedMapOf("assets/collision" to "file", "assets/collision/child" to "fails"))), 6)
        assertEquals(old, manager.allPluginList.single())
        assertEquals(3, metadata().version)
        assertTrue(directory.resolve("lock").exists())
        assertFalse(directory.resolve("plugin").exists())
    }

    @Test fun replacementRejectsUnsupportedApiAndKeepsPreviousMetadata() = runBlocking {
        assertCompleted(install(apk()))
        val old = metadata()
        entryClass = UnsupportedPlugin::class.java
        val states = install(apk())
        assertFailed(states, 4, PluginInstallError.PluginNotSupport::class.java)
        // The existing error reports the installed API, not the incoming API.
        assertEquals(old.apiVersion, ((states.last() as InstallState.Error).result as PluginInstallError.PluginNotSupport).pluginUsedApiVersion)
        assertEquals(old, metadata())
        assertEquals(old, manager.allPluginList.single())
        assertFalse(directory.resolve("lock").exists())
    }

    @Test fun replacementRejectsDowngradeBeforeSignatureCheck() = runBlocking {
        assertCompleted(install(apk()))
        val old = metadata().copy(version = 3)
        directory.resolve("metadata.json").writeText(Json.encodeToString(old))
        assertFailed(install(apk()), 4, PluginInstallError.CurrentPluginVersionTooHighError::class.java)
        assertEquals(old, metadata())
        verify(exactly = 0) { getApkSignatures(directory.resolve("plugin")) }
    }

    @Test fun replacementAcceptsEqualSignatures() = runBlocking {
        assertCompleted(install(apk()))
        val replacement = apk()
        signatures[directory.resolve("plugin").absolutePath] = listOf(signature("same"))
        signatures[replacement.absolutePath] = listOf(signature("same"))
        assertCompleted(install(replacement))
        assertTrue(manager.allPluginList.single().hasSignature)
    }

    @Test fun replacementRejectsDifferentSignatures() = runBlocking {
        assertCompleted(install(apk()))
        val replacement = apk()
        signatures[directory.resolve("plugin").absolutePath] = listOf(signature("old"))
        signatures[replacement.absolutePath] = listOf(signature("new"))
        assertFailed(install(replacement), 4, PluginInstallError.PluginSignatureNotMatchError::class.java)
        assertFalse(directory.resolve("lock").exists())
    }

    @Test fun replacementRejectsSignedToUnsigned() = runBlocking {
        assertCompleted(install(apk()))
        signatures[directory.resolve("plugin").absolutePath] = listOf(signature("old"))
        assertFailed(install(apk()), 4, PluginInstallError.PluginSignatureNotMatchError::class.java)
    }

    @Test fun corruptInstalledMetadataStopsBeforeWriting() = runBlocking {
        assertCompleted(install(apk()))
        directory.resolve("metadata.json").writeText("broken")
        assertFailed(install(apk()), 4)
        assertEquals("broken", directory.resolve("metadata.json").readText())
        assertEquals(1, manager.allPluginList.size)
    }

    @Test fun missingInstalledMetadataStopsBeforeWriting() = runBlocking {
        assertCompleted(install(apk()))
        directory.resolve("metadata.json").delete()
        assertFailed(install(apk()), 4)
        assertTrue(directory.resolve("plugin").exists())
    }

    @Test fun blockedInstallationDirectoryFailsDuringWriteWithoutPublishingMetadata() = runBlocking {
        directory.parentFile.mkdirs()
        directory.writeText("not a directory")
        assertFailed(install(apk()), 5)
        assertEquals("not a directory", directory.readText())
        assertTrue(manager.allPluginList.isEmpty())
    }

    @Test fun assetExtractionFailureLeavesLockAndPartialFilesThenRetryPreservesData() = runBlocking {
        val broken = apk(linkedMapOf("assets/collision" to "file", "assets/collision/child" to "fails"))
        assertFailed(install(broken), 6)
        assertTrue(directory.resolve("lock").exists())
        assertTrue(directory.resolve("metadata.json").exists())
        assertFalse(directory.resolve("plugin").exists())
        assertTrue(manager.allPluginList.isEmpty())
        val data = directory.resolve("data/keep").apply { parentFile!!.mkdirs(); writeText("user") }
        assertCompleted(install(apk()))
        assertEquals("user", data.readText())
        assertFalse(directory.resolve("asset/collision").exists())
        assertFalse(directory.resolve("lock").exists())
    }

    @Test fun libraryExtractionFailureStopsBeforeAssetsAndCopy() = runBlocking {
        val apk = apk(linkedMapOf(
            "lib/${Build.SUPPORTED_ABIS.first()}/collision" to "file",
            "lib/${Build.SUPPORTED_ABIS.first()}/collision/child" to "fails",
            "assets/not-extracted" to "asset"
        ))
        assertFailed(install(apk), 6)
        assertTrue(directory.resolve("lock").exists())
        assertFalse(directory.resolve("asset").exists())
        assertFalse(directory.resolve("plugin").exists())
        assertTrue(manager.allPluginList.isEmpty())
    }

    @Test fun librariesUseSupportedAbiPriorityAndExcludeGraphicsPathLibrary() = runBlocking {
        val entries = linkedMapOf<String, String>()
        for (abi in Build.SUPPORTED_ABIS) {
            entries["lib/$abi/libfixture.so"] = abi
            entries["lib/$abi/libandroidx.graphics.path.so"] = "excluded"
        }
        entries["lib/unsupported/libignored.so"] = "ignored"
        assertCompleted(install(apk(entries)))
        assertEquals(Build.SUPPORTED_ABIS.first(), directory.resolve("libs/libfixture.so").readText())
        assertFalse(directory.resolve("libs/libandroidx.graphics.path.so").exists())
        assertFalse(directory.resolve("libs/libignored.so").exists())
        // Legacy cleanup is best-effort: unclosed input streams can retain temp on Windows.
    }

    @Test fun copyFailureLeavesLockAndDoesNotPublishMetadata() = runBlocking {
        // An archive entry creates a directory where the final APK must be copied.
        assertFailed(install(apk(mapOf("assets/../plugin/blocker" to "block"))), 6)
        assertTrue(directory.resolve("lock").exists())
        assertTrue(directory.resolve("plugin").isDirectory)
        assertTrue(manager.allPluginList.isEmpty())
    }

    @Test fun staleLockCleansBeforeMetadataFailureButKeepsData() = runBlocking {
        directory.mkdirs()
        directory.resolve("lock").writeText("")
        directory.resolve("plugin").writeText("partial")
        val data = directory.resolve("data/keep").apply { parentFile!!.mkdirs(); writeText("user") }
        metadataReadable = false
        assertFailed(install(apk()), 3)
        assertFalse(directory.resolve("lock").exists())
        assertFalse(directory.resolve("plugin").exists())
        assertEquals("user", data.readText())
    }

    @Test fun staleLockMakesRetryFollowFirstInstallRules() = runBlocking {
        assertCompleted(install(apk()))
        directory.resolve("lock").writeText("")
        entryClass = UnsupportedPlugin::class.java
        assertCompleted(install(apk()))
        assertEquals(Int.MAX_VALUE, manager.allPluginList.single().apiVersion)
    }

    @Test fun replacementUnloadsBeforeMetadataFailureWithoutReloading() = runBlocking {
        assertCompleted(install(apk()))
        val loaded = mockk<LightNovelReaderPlugin>(relaxed = true)
        (manager.loadedPluginMap as MutableMap)[PACKAGE] = loaded
        every { loaded.onUnload() } answers { assertTrue(directory.resolve("plugin").exists()) }
        metadataReadable = false
        assertFailed(install(apk()), 3)
        verifyOrder { loaded.onUnload(); sources.unloadWebDataSourcesFromClassLoader(PACKAGE) }
        assertTrue(manager.loadedPluginMap.isEmpty())
        assertEquals(1, manager.allPluginList.size)
        verify(exactly = 0) { injector.providePlugin(any(), any()) }
    }

    @Test fun installationSuccessRemainsDistinctFromRuntimeActivationFailure() = runBlocking {
        assertCompleted(install(apk()))
        every { injector.providePlugin(any(), any()) } returns null
        assertTrue(manager.loadPlugin(PACKAGE).isErr)
        assertTrue(manager.loadedPluginMap.isEmpty())
        assertEquals(1, manager.allPluginList.size)
        assertTrue(manager.errorPluginMap.containsKey(PACKAGE))
        assertTrue(directory.resolve("error").exists())
        verify(exactly = 0) { sources.loadWebDataSourcesFromClassLoader(any(), any(), any(), any()) }
    }

    private suspend fun install(apk: File) = manager.installPlugin(apk).toList()
    private fun discoveredAppPlugin() {
        // Seed the discovery result without starting unrelated built-in sources.
        PluginManager::class.java.getDeclaredField("appPluginInfos").apply { isAccessible = true }
            .set(manager, listOf(PluginAppInfo(PACKAGE, "Fixture", "2")))
    }
    private fun metadata() = Json.decodeFromString<PluginMetadata>(directory.resolve("metadata.json").readText())
    private fun signature(hash: String): ApkSignatureInfo = mockk { every { sha256 } returns hash }
    private fun apk(entries: Map<String, String> = emptyMap()): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }
    private fun assertCompleted(states: List<InstallState>) {
        assertEquals(stages, states.dropLast(1))
        assertEquals(PACKAGE, (states.last() as InstallState.Completed).pluginPackage)
    }
    private fun assertFailed(states: List<InstallState>, stageCount: Int, type: Class<out Throwable>? = null) {
        assertEquals(stages.take(stageCount), states.dropLast(1))
        assertTrue(states.last().toString(), states.last() is InstallState.Error)
        if (type != null) assertTrue(type.name, type.isInstance((states.last() as InstallState.Error).result))
        assertFalse(states.any { it is InstallState.Completed })
    }

    @Plugin(name = "Fixture", version = 2, versionName = "2", author = "Test", description = "Test", updateUrl = "", apiVersion = ApiMetadata.API_VERSION)
    class SupportedPlugin
    @Plugin(name = "Fixture", version = 3, versionName = "3", author = "Test", description = "Test", updateUrl = "", apiVersion = ApiMetadata.API_VERSION)
    class UpdatedPlugin
    @Plugin(name = "Fixture", version = 2, versionName = "2", author = "Test", description = "Test", updateUrl = "", apiVersion = Int.MAX_VALUE)
    class UnsupportedPlugin
    companion object { private const val PACKAGE = "test.plugin.installation" }
}
