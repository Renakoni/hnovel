package indi.renakoni.nextvol

import android.app.Application
import android.content.Context
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import indi.renakoni.nextvol.data.logging.LogLevel
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.plugin.PluginManager
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.utils.analytics.MatomoAnalytics
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import javax.inject.Inject

@HiltAndroidApp
class NextVolApplication : Application(), Configuration.Provider, coil3.SingletonImageLoader.Factory {
    @Inject lateinit var sourceImageInterceptor: indi.renakoni.nextvol.data.image.SourceImageInterceptor
    @Inject lateinit var importedRuleSources: indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
    @Inject lateinit var zLibrarySources: indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources

    override fun newImageLoader(context: Context): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(sourceImageInterceptor); add(indi.renakoni.nextvol.data.image.SourceImageFetcher.Factory()) }
        .build()

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var loggerRepository: LoggerRepository
    @Inject lateinit var userDataRepository: UserDataRepository
    @Inject lateinit var pluginManager: PluginManager
    @Inject lateinit var matomoAnalytics: MatomoAnalytics

    override val workManagerConfiguration: Configuration
        get()  =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalFoundationApi::class)
    @ExperimentalSerializationApi
    override fun onCreate() {
        // Hilt's generated super.onCreate injects host repositories. An isolated service has
        // a different UID and must not initialize app files, WorkManager, plugins or analytics.
        if (android.os.Process.myUid() != applicationInfo.uid) return
        val process = java.io.File("/proc/self/cmdline").inputStream().use { input ->
            input.readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
        }
        if (process.endsWith(":source_browser") || process.endsWith(":source_browser_native")) return
        super.onCreate()
        // The new Compose text context menu asks MIUI's action mode to treat the
        // Compose root as a TextView, which leaves a stale "Select all" toolbar.
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        if (BuildConfig.DEBUG) {
            System.setProperty("kotlinx.coroutines.debug", "on")
        }
        // We have to ensure the plugin load before the activity start up, so we use run blocking here though it will block the main thread
        // Discovery treats the first registry snapshot as authoritative, including imported sources.
        // Async startup must add explicit registration readiness before exposing missing/empty states.
        runBlocking {
            pluginManager.initAllPlugin()
            importedRuleSources.restore()
            zLibrarySources.restore()
        }
        coroutineScope.launch(Dispatchers.IO) {
            matomoAnalytics.initialize()
            matomoAnalytics.trackAppLaunch()
            loggerRepository.logLevel = LogLevel.from(userDataRepository.stringUserData(UserDataPath.Settings.Data.LogLevel.path).getOrDefault("none"))
            loggerRepository.startLogging()
        }
    }
}
