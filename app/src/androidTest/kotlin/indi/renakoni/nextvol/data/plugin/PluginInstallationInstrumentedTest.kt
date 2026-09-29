package indi.renakoni.nextvol.data.plugin

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import dagger.hilt.android.EntryPointAccessors
import dalvik.system.PathClassLoader
import indi.renakoni.nextvol.data.plugin.install.InstallState
import indi.renakoni.nextvol.data.plugin.install.PluginInstallError
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.utils.getApkSignatures
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PluginInstallationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val apk = File(instrumentation.context.applicationInfo.sourceDir)
    private val packageName = instrumentation.context.packageName
    private val sourceId = Identifier("installation-test", "fixture")
    private val rejection = File(context.cacheDir, "plugin-fixture-reject-source")
    private lateinit var root: File
    private lateinit var manager: PluginManager
    private lateinit var entry: PluginInstallationDebugEntryPoint

    @Before fun setup() {
        root = File(context.cacheDir, "plugin-installation-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getDataDir() = root.resolve("data").apply { mkdirs() }
            override fun getCacheDir() = root.resolve("cache").apply { mkdirs() }
        }
        entry = EntryPointAccessors.fromApplication(context, PluginInstallationDebugEntryPoint::class.java)
        manager = PluginManager(isolated, entry.sources(), entry.injector(), entry.users(), entry.networkSettings())
        rejection.delete()
    }

    @After fun cleanup() = runBlocking(Dispatchers.IO) {
        rejection.delete()
        manager.deletePlugin(packageName)
        root.walkTopDown().forEach { it.setWritable(true) }
        root.deleteRecursively()
        Unit
    }

    @Test fun signedApkInstallsLoadsServesContentAndReinstallsWithoutLosingData() = runBlocking(Dispatchers.IO) {
        assertFalse(getApkSignatures(apk).isNullOrEmpty())
        assertInstalled(manager.installPlugin(apk).toList())
        assertTrue(manager.loadedPluginMap.isEmpty())
        val directory = manager.getPluginDir(packageName)
        assertEquals("Plugin installation fixture", directory.resolve("asset/plugin-installation-fixture.txt").readText().trim())
        assertTrue(manager.allPluginList.single().hasSignature)
        assertTrue(manager.loadPlugin(packageName).isOk)
        assertTrue(manager.loadedPluginMap.getValue(packageName).javaClass.classLoader is PathClassLoader)
        val ready = entry.sources().registry.resolve(sourceId) as SourceResolution.Ready
        assertEquals("Fixture chapter", ready.runtime.getChapterContent("chapter", "book").get()!!.title)
        val lifecycle = directory.resolve("data/lifecycle")
        assertEquals("load\n", lifecycle.readText())
        directory.resolve("data/user-value").writeText("keep")

        assertInstalled(manager.installPlugin(apk).toList())
        assertEquals("load\nunload\n", lifecycle.readText())
        assertTrue(manager.loadedPluginMap.isEmpty())
        assertFalse(ready.runtime.isAvailable)
        assertTrue(entry.sources().registry.resolve(sourceId) is SourceResolution.Missing)
        assertEquals("keep", directory.resolve("data/user-value").readText())
        assertTrue(manager.loadPlugin(packageName).isOk)
        assertEquals("load\nunload\nload\n", lifecycle.readText())
        manager.unloadPlugin(packageName)
        assertEquals("load\nunload\nload\nunload\n", lifecycle.readText())
        assertTrue(entry.sources().registry.resolve(sourceId) is SourceResolution.Missing)
    }

    @Test fun realPackageManagerRejectsNonApkBeforeAnyInstallationFiles() = runBlocking(Dispatchers.IO) {
        val invalid = root.resolve("invalid.apk").apply { writeText("not an apk") }
        val states = manager.installPlugin(invalid).toList()
        assertEquals(2, states.size)
        assertEquals(InstallState.Start.ParsePackageInfo, states.first())
        assertTrue(states.last() is InstallState.Error)
        assertFalse(manager.pluginsDir.exists())
        assertTrue(manager.allPluginList.isEmpty())
    }

    @Test fun realSignatureCheckRejectsUnsignedReplacement() = runBlocking(Dispatchers.IO) {
        assertInstalled(manager.installPlugin(apk).toList())
        val unsigned = root.resolve("unsigned.apk")
        // Repacking removes the APK signing block and v1 signatures without a private key.
        ZipFile(apk).use { zip ->
            ZipOutputStream(unsigned.outputStream()).use { output ->
                zip.entries().asSequence().filter { !it.name.startsWith("META-INF/") }.forEach { file ->
                    output.putNextEntry(ZipEntry(file.name))
                    if (!file.isDirectory) zip.getInputStream(file).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        assertTrue(getApkSignatures(unsigned).isNullOrEmpty())
        val states = manager.installPlugin(unsigned).toList()
        assertEquals(InstallState.Start.CheckPluginInstallLegality, states[states.lastIndex - 1])
        assertTrue((states.last() as InstallState.Error).result is PluginInstallError.PluginSignatureNotMatchError)
        assertFalse(states.any { it is InstallState.Completed })
        assertTrue(manager.allPluginList.single().hasSignature)
        assertFalse(manager.getPluginDir(packageName).resolve("lock").exists())
    }

    @Test fun sourceRegistrationFailureDoesNotBecomeLoadedSuccess() = runBlocking(Dispatchers.IO) {
        assertInstalled(manager.installPlugin(apk).toList())
        rejection.writeText("reject")
        assertTrue(manager.loadPlugin(packageName).isErr)
        assertTrue(manager.loadedPluginMap.isEmpty())
        assertTrue(manager.errorPluginMap.containsKey(packageName))
        assertTrue(manager.getPluginDir(packageName).resolve("error").exists())
        assertTrue(entry.sources().registry.resolve(sourceId) is SourceResolution.Missing)
        assertEquals(1, manager.allPluginList.size)
        rejection.delete()
        assertTrue(manager.loadPlugin(packageName).isOk)
        assertFalse(manager.errorPluginMap.containsKey(packageName))
    }

    private fun assertInstalled(states: List<InstallState>) {
        assertEquals(listOf(
            InstallState.Start.ParsePackageInfo, InstallState.Start.Clean,
            InstallState.Start.ParsePluginMetadata, InstallState.Start.CheckPluginInstallLegality,
            InstallState.Start.WritePluginMetadataToFile, InstallState.Start.CopyPlugin
        ), states.dropLast(1))
        assertEquals(packageName, (states.last() as InstallState.Completed).pluginPackage)
    }
}
