package indi.renakoni.nextvol.data.update

import android.util.Log
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * LNR API 更新源
 * 通过 LNR API 获取更新信息
 */
object APIParser {
    private const val TAG = "APIParser"
    private const val BASE_URL = "https://lnr.nariko.org"
    private const val API_PATH = "/api/update"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    class APIRelease(
        override val version: Int,
        override val versionName: String,
        override val releaseNotes: String,
        override val downloadUrl: String,
        override val downloadFileProgress: ((File, File) -> Unit)? = null
    ) : Release

    private fun fetchUpdate(
        channel: String,
        updatePhase: MutableStateFlow<UpdatePhase>,
        allowZipFallback: Boolean = false
    ): Release? {
        return try {
            updatePhase.tryEmit(UpdatePhase(R.string.update_phase_api_request, listOf(channel)))
            val request = Request.Builder()
                .url("$BASE_URL$API_PATH?channel=$channel&ref=lnr-app&ver=${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/json")
                .get()
                .build()
            val responseBody = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "API request failed with status code: ${response.code}")
                    return null
                }
                response.body.string()
            }

            updatePhase.tryEmit(UpdatePhase(R.string.update_phase_api_parse))
            val json = JSONObject(responseBody)

            if (json.has("error")) {
                Log.e(TAG, "API returned error: ${json.getString("error")}")
                return null
            }

            val versionCode = json.getInt("version_code")
            val versionName = json.getString("version")
            val releaseNotes = json.getString("release_notes")

            updatePhase.tryEmit(UpdatePhase(R.string.update_phase_api_download_link))
            val artifacts = json.getJSONArray("artifacts")
            var downloadUrl: String? = null
            var isZip = false
            for (i in 0 until artifacts.length()) {
                val artifact = artifacts.getJSONObject(i)
                if (artifact.getString("name").endsWith(".apk")) {
                    downloadUrl = artifact.getString("download_url")
                    break
                }
            }
            if (downloadUrl == null && allowZipFallback) {
                for (i in 0 until artifacts.length()) {
                    val artifact = artifacts.getJSONObject(i)
                    val contentType = artifact.optString("content_type", "")
                    if (contentType.contains("zip")) {
                        downloadUrl = artifact.getString("download_url")
                        isZip = true
                        break
                    }
                }
            }

            if (downloadUrl == null) {
                Log.e(TAG, "No suitable artifact found in API response")
                return null
            }

            val downloadFileProgress: ((File, File) -> Unit)? = if (isZip) { zipFile, targetApk ->
                try {
                    ZipFile(zipFile).use { zip ->
                        val apkEntry = zip.entries().asSequence()
                            .filterNot { it.isDirectory }
                            .find { entry ->
                                entry.name.endsWith(".apk") &&
                                        "release" in entry.name
                            }
                            ?: throw MissingUpdateApkException(zipFile.name)

                        targetApk.parentFile?.mkdirs()
                        if (targetApk.exists()) targetApk.delete()

                        zip.getInputStream(apkEntry).use { input ->
                            targetApk.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解压失败: ${e.message}")
                    targetApk.delete()
                    throw e
                }
            } else null

            updatePhase.tryEmit(UpdatePhase(R.string.update_phase_api_complete))
            APIRelease(
                version = versionCode,
                versionName = versionName,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                downloadFileProgress = downloadFileProgress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch update from API: ${e.message}", e)
            null
        }
    }

    object StableParser : UpdateParser {
        override fun parser(updatePhase: MutableStateFlow<UpdatePhase>): Release? {
            return fetchUpdate("stable", updatePhase)
        }
    }

    object BetaParser : UpdateParser {
        override fun parser(updatePhase: MutableStateFlow<UpdatePhase>): Release? {
            return fetchUpdate("beta", updatePhase)
        }
    }

    object UnstableParser : UpdateParser {
        override fun parser(updatePhase: MutableStateFlow<UpdatePhase>): Release? {
            return fetchUpdate("unstable", updatePhase, allowZipFallback = true)
        }
    }
}
