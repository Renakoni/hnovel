package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.webkit.WebView
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewFeature
import hnovel.network.SourceScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

internal fun nativeBrowserProfile(scope: SourceScope): String = MessageDigest.getInstance("SHA-256")
    .digest(Json.encodeToString(listOf(scope.namespace, scope.sourceId, scope.profile,
        scope.accountGeneration.toString())).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Profile files move only after the dedicated Chromium process has exited. Never copy a live DB. */
internal class NativeBrowserFiles(private val context: Context) {
    private val relocated get() = WebViewFeature.isStartupFeatureSupported(context, WebViewFeature.STARTUP_FEATURE_SET_DIRECTORY_BASE_PATHS)
    private val root = File(context.noBackupFilesDir, "source-browser")
    private val active = File(context.applicationInfo.dataDir, "app_webview_source_native")
    private val activeCache = File(context.cacheDir, "webview_source_native")
    private val ownerFile = AtomicFile(File(root, "active-owner"))
    val supported get() = relocated || Build.VERSION.SDK_INT >= 28

    fun directory(profile: String): File {
        require(profile.matches(Regex("[0-9a-f]{64}")))
        return File(root, profile).also { check(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    private fun owner(): String? = try {
        ownerFile.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: FileNotFoundException) { null }

    fun exists(profile: String): Boolean = directory(profile).exists() || !relocated && owner() == profile && active.exists()

    fun prepare(profile: String) {
        directory(profile)
        if (relocated) return
        check(Build.VERSION.SDK_INT >= 28)
        check(root.exists() || root.mkdirs())
        val previous = owner()
        if (previous != profile) check(!activeCache.exists() || activeCache.deleteRecursively())
        if (active.exists() && previous != profile) {
            val saved = File(directory(checkNotNull(previous)), "webview")
            check(saved.parentFile!!.exists() || saved.parentFile!!.mkdirs())
            check(!saved.exists() && active.renameTo(saved)) { "Browser profile could not be parked" }
        }
        val output = ownerFile.startWrite()
        try { output.write(profile.toByteArray()); ownerFile.finishWrite(output) }
        catch (failure: Exception) { ownerFile.failWrite(output); throw failure }
        val saved = File(directory(profile), "webview")
        if (!active.exists() && saved.exists()) check(saved.renameTo(active)) { "Browser profile could not be restored" }
    }

    fun initialize(profile: String) {
        if (relocated) {
            ProcessGlobalConfig.apply(ProcessGlobalConfig().setDirectoryBasePaths(context, directory(profile),
                File(context.cacheDir, "source-browser/$profile")))
        } else {
            check(Build.VERSION.SDK_INT >= 28 && owner() == profile)
            WebView.setDataDirectorySuffix("source_native")
        }
    }

    fun clear(profile: String) {
        val directory = directory(profile)
        if (!relocated && owner() == profile) {
            check(!active.exists() || active.deleteRecursively())
            check(!activeCache.exists() || activeCache.deleteRecursively())
            ownerFile.delete()
        }
        check(!directory.exists() || directory.deleteRecursively())
        val cache = File(context.cacheDir, "source-browser/$profile")
        check(!cache.exists() || cache.deleteRecursively())
    }
}
