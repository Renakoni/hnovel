package indi.renakoni.nextvol.data.plugin.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.github.michaelbull.result.runCatching
import indi.renakoni.nextvol.data.plugin.PluginMetadata
import indi.renakoni.nextvol.utils.classLoader
import indi.renakoni.nextvol.utils.getApkSignatures
import indi.renakoni.nextvol.utils.isSignatureMatch
import io.nightfish.lightnovelreader.api.ApiCompat
import io.nightfish.lightnovelreader.api.plugin.Plugin
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/** APK inspection and installation files only; does not activate plugins or own runtime state. */
internal class PluginInstaller(private val appContext: Context) {
    val pluginsDir: File = appContext.dataDir.resolve("plugins")

    fun getPluginDir(name: String): File = pluginsDir.resolve(name)
    fun getPluginDataDir(pluginDir: File): File = pluginDir.resolve("data")
    fun getPluginFile(pluginDir: File): File = pluginDir.resolve("plugin")
    fun getPluginAssetDir(pluginDir: File): File = pluginDir.resolve("asset")
    fun getPluginLibsDir(pluginDir: File): File = pluginDir.resolve("libs")
    fun getPluginInstallLock(pluginDir: File): File = pluginDir.resolve("lock")
    fun getPluginMetadataFile(pluginDir: File): File = pluginDir.resolve("metadata.json")

    fun readPackageName(plugin: File): String? = appContext.packageManager.getPackageArchiveInfo(
        plugin.path,
        PackageManager.GET_PERMISSIONS
    )?.packageName

    fun cleanInterruptedInstallation(pluginDir: File) {
        if (getPluginInstallLock(pluginDir).exists()) {
            deletePluginWithoutData(pluginDir)
        }
    }

    private fun deletePluginWithoutData(pluginDir: File) {
        pluginDir.listFiles {
            it.name != "data"
        }?.forEach {
            it.deleteRecursively()
        }
    }

    fun readMetadata(file: File, packageName: String): Result<PluginMetadata, Throwable> =
        runCatching {
            if (file.canWrite() && !file.setReadOnly()) error("Failed to set read-only plugin file")
            val pluginClassName = appContext.packageManager
                .getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
                ?.applicationInfo?.metaData?.getString("lnr_plugin")
                ?: error("lnr_plugin not found in manifest meta-data of ${file.name}")
            val cl = classLoader(file.absolutePath, null, this.javaClass.classLoader)
            cl.loadClass(pluginClassName)
        }.andThen {
            runCatching {
                val plugin = it.getAnnotation(Plugin::class.java)
                    ?: return@andThen Err(Error("Failed to get plugin annotation from the plugin class"))
                PluginMetadata.parse(plugin, packageName, getApkSignatures(file)?.isNotEmpty() == true)
            }
        }

    fun checkLegality(
        plugin: File,
        pluginDir: File,
        newPluginMetadata: PluginMetadata,
        appPluginPackages: List<String>
    ): Result<Unit, Throwable> {
        val currentPluginApk = getPluginFile(pluginDir)
        // Preserve the existing first-install/overwrite distinction, including lock recovery.
        if (!currentPluginApk.exists()) return Ok(Unit)
        return runCatching {
            getPluginMetadataFile(pluginDir)
                .inputStream()
                .use {
                    Json.decodeFromString<PluginMetadata>(it.readBytes().decodeToString())
                }
                .also { println(it) }
        }.andThen { currentPluginMetadata ->
            if (newPluginMetadata.packageName in appPluginPackages) {
                return@andThen Err(PluginInstallError.AppPluginExist())
            }
            if (!ApiCompat.isSupported(newPluginMetadata.apiVersion)) {
                return@andThen Err(PluginInstallError.PluginNotSupport(currentPluginMetadata.apiVersion))
            }
            if (currentPluginMetadata.version > newPluginMetadata.version) {
                return@andThen Err(PluginInstallError.CurrentPluginVersionTooHighError())
            }
            if (!isSignatureMatch(
                    getApkSignatures(currentPluginApk),
                    getApkSignatures(plugin)
                )
            ) {
                return@andThen Err(PluginInstallError.PluginSignatureNotMatchError())
            }
            return@andThen Ok(Unit)
        }
    }

    fun writeMetadata(pluginDir: File, metadata: PluginMetadata): Result<Unit, Throwable> = runCatching {
        deletePluginWithoutData(pluginDir)
        pluginDir.mkdirs()
        getPluginInstallLock(pluginDir).createNewFile()
        getPluginMetadataFile(pluginDir)
            .outputStream()
            .use {
                it.write(Json.encodeToString<PluginMetadata>(metadata).toByteArray())
            }
    }

    fun copyPlugin(plugin: File, pluginDir: File): Result<Unit, Throwable> =
        extractLibFromApk(plugin, getPluginLibsDir(pluginDir)).andThen {
            extractAssetFromApk(plugin, getPluginAssetDir(pluginDir))
        }.andThen {
            runCatching {
                val target = getPluginFile(pluginDir)
                plugin.inputStream().buffered().use { inputStream ->
                    target.outputStream().buffered().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }.map {
            getPluginInstallLock(pluginDir).delete()
            Unit
        }

    private fun extractAssetFromApk(apk: File, targetDir: File) = runCatching {
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.startsWith("assets/")) {
                    zip.getInputStream(entry).buffered().use { input ->
                        val out = targetDir.resolve(entry.name.removePrefix("assets/"))
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun extractLibFromApk(apk: File, targetDir: File) = runCatching {
        val tempDir = targetDir.resolve("temp").also { it.mkdir() }
        val packageInfo = appContext.packageManager.getPackageArchiveInfo(apk.path, 0)
        packageInfo?.applicationInfo?.let {
            ZipFile(apk.path).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (
                        entry.name.startsWith("lib/") &&
                        !entry.isDirectory &&
                        !entry.name.endsWith("libandroidx.graphics.path.so")
                    ) {
                        val out = tempDir.resolve(entry.name.removePrefix("lib/"))
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).buffered().use { input ->
                            out.outputStream().buffered().use { input.copyTo(it) }
                        }
                    }
                }
            }
        }
        val abiList = Build.SUPPORTED_ABIS
        for (abi in abiList.reversed()) {
            val abiDir = tempDir.resolve(abi)
            if (!abiDir.exists()) continue
            abiDir.listFiles()?.forEach { file ->
                val outputFile = targetDir.resolve(file.name)
                outputFile.parentFile?.mkdirs()
                if (!outputFile.exists()) outputFile.createNewFile()
                outputFile.outputStream().buffered().use {
                    file.inputStream().buffered().copyTo(it)
                }
            }
        }
        tempDir.deleteRecursively()
        return@runCatching
    }
}
