package indi.renakoni.nextvol.data.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation.NavGraphBuilder
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.PathClassLoader
import indi.renakoni.nextvol.data.plugin.injector.PluginInjector
import indi.renakoni.nextvol.data.plugin.install.InstallState
import indi.renakoni.nextvol.data.plugin.install.PluginInstaller
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.WebBookDataSourceManager
import indi.renakoni.nextvol.data.web.SourceNetworkSettings
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import indi.renakoni.nextvol.utils.classLoader
import indi.renakoni.nextvol.utils.getApkSignatures
import io.nightfish.lightnovelreader.api.ApiCompat
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.plugin.PluginConstants
import io.nightfish.lightnovelreader.api.plugin.PluginContext
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor(
    @field:ApplicationContext private val appContext: Context,
    private val webBookDataSourceManager: WebBookDataSourceManager,
    private val pluginInjector: PluginInjector,
    userDataRepository: UserDataRepository,
    private val networkSettings: SourceNetworkSettings,
) {
    companion object {
        const val TAG = "PluginManager"
    }

    private val mutableAllPluginMetadataList = mutableStateListOf<PluginMetadata>()
    val allPluginList: List<PluginMetadata> get() = mutableAllPluginMetadataList
    private val mutableLoadedPluginMap = mutableMapOf<String, LightNovelReaderPlugin>()
    val loadedPluginMap: Map<String, LightNovelReaderPlugin> get() = mutableLoadedPluginMap
    private val mutableErrorPluginMap = mutableMapOf<String, String>()
    val errorPluginMap: Map<String, String> get() = mutableErrorPluginMap

    private val enabledPluginsUserData =
        userDataRepository.stringListUserData(UserDataPath.Plugin.EnabledPlugins.path)

    private val pluginInstaller = PluginInstaller(appContext)
    val pluginsDir: File = pluginInstaller.pluginsDir
    val pluginsTempDir: File = appContext.cacheDir.resolve("plugins_tmp")
    var appPluginInfos: List<PluginAppInfo> = emptyList()
       private set

    private val onInitializedCallbacks = mutableListOf<() -> Unit>()
    fun addOnInitializedCallback(callback: () -> Unit) {
        onInitializedCallbacks += callback
    }
    fun getPluginDir(name: String): File = pluginInstaller.getPluginDir(name)
    fun getPluginDataDir(pluginDir: File) = pluginInstaller.getPluginDataDir(pluginDir)
    fun getPluginFile(pluginDir: File): File = pluginInstaller.getPluginFile(pluginDir)
    fun getPluginAssetDir(pluginDir: File): File = pluginInstaller.getPluginAssetDir(pluginDir)
    fun getPluginLibsDir(pluginDir: File): File = pluginInstaller.getPluginLibsDir(pluginDir)
    private fun getPluginInstallLock(pluginDir: File) = pluginInstaller.getPluginInstallLock(pluginDir)
    private fun getPluginLoadError(pluginDir: File) = pluginDir.resolve("error")
    private fun getPluginMetadataFile(pluginDir: File): File = pluginInstaller.getPluginMetadataFile(pluginDir)

    suspend fun unloadPlugin(packageName: String) {
        loadedPluginMap[packageName]?.onUnload()
        mutableLoadedPluginMap.remove(packageName)
        webBookDataSourceManager.unloadWebDataSourcesFromClassLoader(packageName)
        val enabledPlugins = enabledPluginsUserData.getOrDefault(emptyList()).toMutableList()
        if (packageName in enabledPlugins) {
            enabledPlugins -= packageName
            enabledPluginsUserData.asynchronousSet(enabledPlugins)
        }
    }

    suspend fun initAllAppPlugin(): List<PluginAppInfo> {
        val intent = Intent(PluginConstants.DISCOVERY_ACTION)
        val receivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryBroadcastReceivers(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            appContext.packageManager.queryBroadcastReceivers(intent, PackageManager.MATCH_ALL)
        }

        return receivers.mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null.also {
                Log.e(TAG, "failed to get app info")
            }
            if (appInfo.packageName == appContext.packageName) return@mapNotNull null

            val packageName = appInfo.packageName
            val apkPath = appInfo.sourceDir ?: return@mapNotNull null.also {
                Log.e(TAG, "failed to get apk file path")
            }

            val appLabel = runCatching { appInfo.loadLabel(appContext.packageManager).toString() }
                .getOrElse { packageName }

            val versionName = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0L)
                    ).versionName
                } else {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageInfo(packageName, 0).versionName
                }
            }.get() ?: ""

            runCatching {
                val apkFile = File(apkPath)
                var error: InstallState.Error? = null
                installPlugin(apkFile).collect {
                    when (it) {
                        is InstallState.Error -> error = it
                        is InstallState.Completed -> Log.i(TAG, "App plugin successfully installed (package=$packageName)")
                        else -> {}
                    }
                }
                if (error != null) {
                    Log.e(TAG, "Failed to install app plugin")
                    error.result.printStackTrace()
                    return@mapNotNull null
                }
            }
            return@mapNotNull PluginAppInfo(
                packageName = packageName,
                name =appLabel,
                versionName = versionName
            )
        }
    }

    suspend fun initAllPlugin() {
        pluginsTempDir.deleteRecursively()
        webBookDataSourceManager.loadBuiltInSource(Wenku8Api { id -> networkSettings.forSource(id).snapshot() },
            indi.renakoni.nextvol.data.web.SourceCategory.Anime)
        appPluginInfos = initAllAppPlugin()
        val enabledPlugins = enabledPluginsUserData.getOrDefault(emptyList())
        val pluginDirs = pluginsDir.listFiles()
        if (pluginDirs != null) {
        for (dir in pluginDirs) {
            if (getPluginInstallLock(dir).exists()) continue
            val metadataFile = getPluginMetadataFile(dir)
            if (!metadataFile.exists()) {
                Log.w(TAG, "metadata.json not found in ${dir.name}, skipping")
                continue
            }
            mutableAllPluginMetadataList.removeAll {
                it.packageName == dir.name
            }
            val metadata = try {
                metadataFile.inputStream().use {
                    Json.decodeFromString<PluginMetadata>(it.readBytes().decodeToString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse metadata for ${dir.name}", e)
                continue
            }.let { metadata ->
                if (metadata.packageName in appPluginInfos.map { it.packageName }) {
                    metadata.copy(
                        source = PluginSource.InstalledApp
                    )
                } else metadata
            }
            metadata.also(mutableAllPluginMetadataList::add)
            if (enabledPlugins.contains(dir.name) && ApiCompat.isSupported(metadata.apiVersion)) {
                loadPlugin(dir.name).onErr {
                    Log.e(TAG, "failed to load plugin ${dir.name}")
                    it.printStackTrace()
                }
            }
        }
        }
        onInitializedCallbacks.forEach { it() }
    }

    fun installPlugin(
        plugin: File
    ): Flow<InstallState> = flow {
        emit(InstallState.Start.ParsePackageInfo)
        val packageName = pluginInstaller.readPackageName(plugin)
        if (packageName == null) {
            emit(InstallState.Error(Error("Failed to get package info from APK file: ${plugin.name}")))
            return@flow
        }
        emit(InstallState.Start.Clean)
        val pluginDir = getPluginDir(packageName)
        pluginInstaller.cleanInterruptedInstallation(pluginDir)
        loadedPluginMap[packageName]?.let {
            unloadPlugin(packageName)
        }

        emit(InstallState.Start.ParsePluginMetadata)
        val newPluginMetadata = pluginInstaller.readMetadata(plugin, packageName).onErr {
            emit(InstallState.Error(it))
            return@flow
        }.get() ?: return@flow

        emit(InstallState.Start.CheckPluginInstallLegality)

        pluginInstaller.checkLegality(
            plugin, pluginDir, newPluginMetadata, appPluginInfos.map { it.packageName }
        ).onErr {
            emit(InstallState.Error(it))
            return@flow
        }

        emit(InstallState.Start.WritePluginMetadataToFile)
        pluginInstaller.writeMetadata(pluginDir, newPluginMetadata).onErr {
            emit(InstallState.Error(it))
            return@flow
        }

        emit(InstallState.Start.CopyPlugin)
        pluginInstaller.copyPlugin(plugin, pluginDir).onErr {
            emit(InstallState.Error(it))
            return@flow
        }
        mutableAllPluginMetadataList.removeAll { it.packageName == packageName }
        mutableAllPluginMetadataList.add(newPluginMetadata)
        emit(InstallState.Completed(packageName))
    }
        .flowOn(Dispatchers.IO)

    fun markPluginError(packageName: String, message: String) {
        val pluginDir = getPluginDir(packageName)
            .also { it.mkdirs() }
        val error = getPluginLoadError(pluginDir)
        error.outputStream().buffered().use {
            it.write(message.toByteArray())
        }
        mutableErrorPluginMap[packageName] = message
    }

    fun getPluginError(packageName: String) {
        getPluginLoadError(getPluginDir(packageName))
    }

    suspend fun loadPlugin(
        pluginPackage: String
    ): Result<PluginMetadata, Throwable> {
        val pluginDir = getPluginDir(pluginPackage)
        val packageInfo = appContext.packageManager.getPackageArchiveInfo(
            getPluginFile(pluginDir).absolutePath,
            PackageManager.GET_PERMISSIONS
        ) ?: run {
            val error = Error("Failed to get package info for plugin: $pluginPackage")
            Log.e(TAG, "loadPlugin($pluginPackage): ${error.message}", error)
            return Err(error)
        }
        val plugin = getPluginFile(pluginDir)
        return runCatching {
            plugin.setReadOnly()
        }.andThen {
            getPluginMetadataAndPluginClass(packageInfo.packageName)
        }.andThen { pair ->
            mutableErrorPluginMap.remove(pluginPackage)
            val pluginClazz = pair.second
            val pluginContext = PluginContext(
                packageName = packageInfo.packageName,
                dataDir = getPluginDataDir(pluginDir),
                pluginFile = plugin,
                assetDir = getPluginAssetDir(pluginDir)
            )
            val instance = pluginInjector.providePlugin(
                pluginClazz,
                pluginContext
            )
                ?: return@andThen Err(Error("Failed to create instance of plugin class: ${pluginClazz.name}"))
            instance.onLoad()

            val classLoader = instance.javaClass.classLoader
            if (classLoader !is PathClassLoader) return@andThen Err(Error("Failed to get DexClassLoader from plugin instance, got: ${classLoader?.javaClass?.name}"))
            val webDataSourceClassNames = appContext.packageManager
                .getPackageArchiveInfo(plugin.absolutePath, PackageManager.GET_META_DATA)
                ?.applicationInfo?.metaData?.getString("lnr_web_data_source")
                ?.split(";")
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            runCatching {
                webBookDataSourceManager.loadWebDataSourcesFromClassLoader(
                    classLoader,
                    pluginInjector,
                    pluginPackage,
                    webDataSourceClassNames
                )
            }.onErr {
                    markPluginError(pluginPackage, it.message.toString())
                    unloadPlugin(pluginPackage)
                    return@andThen Err(it)
            }

            mutableLoadedPluginMap[pluginPackage] = instance
            return@andThen Ok(pair.first)
        }.onErr { error ->
            Log.e(
                TAG,
                "loadPlugin($pluginPackage) failed: ${error.message ?: error.toString()}",
                error
            )
            markPluginError(pluginPackage, error.toString())
        }
    }

    suspend fun deletePlugin(packageName: String) {
        unloadPlugin(packageName)
        getPluginDir(packageName).deleteRecursively()
        mutableAllPluginMetadataList.removeAll { it.packageName == packageName }
    }

    private fun getPluginMetadataAndPluginClass(packageName: String): Result<Pair<PluginMetadata, Class<*>>, Throwable> =
        runCatching {
            val pluginDir = getPluginDir(packageName)
            val pluginFile = getPluginFile(pluginDir)
            val pluginClassName = appContext.packageManager
                .getPackageArchiveInfo(pluginFile.absolutePath, PackageManager.GET_META_DATA)
                ?.applicationInfo?.metaData?.getString("lnr_plugin")
                ?: error("lnr_plugin not found in manifest meta-data of $packageName")
            val cl = classLoader(
                pluginFile.absolutePath,
                getPluginLibsDir(pluginDir).absolutePath,
                this.javaClass.classLoader
            )
            Pair(cl, pluginClassName)
        }.andThen { (cl, pluginClassName) ->
            runCatching {
                val clazz = cl.loadClass(pluginClassName)
                val plugin = clazz.getAnnotation(Plugin::class.java)
                    ?: return@andThen Err(Error("Failed to get plugin annotation from the plugin class"))
                Pair(
                    PluginMetadata.parse(
                        plugin,
                        packageName,
                        getApkSignatures(getPluginFile(getPluginDir(packageName)))?.isNotEmpty() == true
                    ),
                    clazz
                )
            }
        }

    @Composable
    fun PluginContent(packageName: String, paddingValues: PaddingValues) {
        loadedPluginMap[packageName]?.PageContent(paddingValues)
    }

    fun NavGraphBuilder.onBuildNavHost() {
        loadedPluginMap.values.forEach {
            with(it) {
                onBuildNavHost()
            }
        }
    }
}
