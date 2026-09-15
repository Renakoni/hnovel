package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import hnovel.network.FailureCode
import hnovel.network.LocalStorageRetention
import hnovel.network.sourceOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LocalStorageFailure(val code: FailureCode = FailureCode.StorageUnavailable) : IOException(code.name)

/** Only controlled, network-disabled origin contexts. Never executes a website or edits its databases. */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun transferLocalStorage(context: Context, job: LocalStorageJob): Map<String, Map<String, String>> =
    withContext(Dispatchers.Main.immediate) {
        job.values?.let(job.selection::validate)
        val view = WebView(context)
        try {
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
            }
            view.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; blockNetworkLoads = true
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            val values = linkedMapOf<String, Map<String, String>>()
            for ((origin, keys) in job.selection.origins.filterValues { it.isNotEmpty() }) {
                suspendCancellableCoroutine { continuation ->
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        override fun onPageFinished(view: WebView, url: String) {
                            if (sourceOrigin(url) == origin && continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame && continuation.isActive) continuation.resumeWithException(LocalStorageFailure())
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (continuation.isActive) continuation.resumeWithException(LocalStorageFailure())
                            return true
                        }
                    }
                    view.loadDataWithBaseURL("$origin/",
                        "<html><head><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'\"></head><body></body></html>",
                        "text/html", "UTF-8", null)
                }
                val restore = job.values?.let { snapshot ->
                    """var saved=JSON.parse(${JsonPrimitive(Json.encodeToString(snapshot[origin].orEmpty()))});
                        Object.keys(saved).forEach(function(key){localStorage.setItem(key,saved[key]);});"""
                }.orEmpty()
                val script = """(function(){
                    $restore
                    var values=Object.create(null), keys=${Json.encodeToString(keys)}, size=${origin.length};
                    for(var i=0;i<keys.length;i++) {
                        var value=localStorage.getItem(keys[i]);
                        if(value!==null) {
                            size+=keys[i].length+value.length;
                            if(value.length>${LocalStorageRetention.MAX_VALUE_CHARS} || size>${LocalStorageRetention.MAX_TOTAL_CHARS})
                                return JSON.stringify({failure:'StorageQuota'});
                            values[keys[i]]=value;
                        }
                    }
                    return JSON.stringify({origin:location.origin,values:values});
                })()""".trimIndent()
                val encoded = suspendCancellableCoroutine { continuation ->
                    view.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
                }
                val result = Json.parseToJsonElement(Json.parseToJsonElement(encoded).jsonPrimitive.content).jsonObject
                if (result["failure"]?.jsonPrimitive?.content == "StorageQuota") throw LocalStorageFailure(FailureCode.StorageQuota)
                check(sourceOrigin(result.getValue("origin").jsonPrimitive.content) == origin)
                values[origin] = Json.decodeFromJsonElement<Map<String, String>>(result.getValue("values"))
                try { job.selection.validate(values) }
                catch (_: IllegalArgumentException) { throw LocalStorageFailure(FailureCode.StorageQuota) }
            }
            values.filterValues { it.isNotEmpty() }
        } finally { view.stopLoading(); view.destroy() }
    }
