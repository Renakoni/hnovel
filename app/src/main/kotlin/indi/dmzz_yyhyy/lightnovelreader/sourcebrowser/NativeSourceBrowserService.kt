package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.*
import android.webkit.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** Native website execution only. No JavascriptInterface, response rewriting or Cookie export. */
class NativeSourceBrowserService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var profile: String? = null
    private var page: Page? = null
    internal var activity: NativeSourceBrowserActivity? = null
    internal val webView get() = page?.view
    internal val title get() = page?.job?.options?.title.orEmpty()
    companion object { internal var active: NativeSourceBrowserService? = null }

    override fun onBind(intent: Intent): IBinder = object : IBrowserService.Stub() {
        override fun start(payload: String, callback: IBrowserHost) {
            check(Binder.getCallingUid() == applicationInfo.uid && payload.length <= 393216)
            val job = Json.decodeFromString<BrowserJob>(payload)
            handler.post {
                try {
                    check(page == null)
                    if (profile == null) {
                        NativeBrowserFiles(this@NativeSourceBrowserService).initialize(job.profile)
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                        profile = job.profile
                    }
                    check(profile == job.profile)
                    active = this@NativeSourceBrowserService
                    page = Page(job, callback).also { it.open() }
                } catch (_: Exception) {
                    page?.view?.destroy(); page = null
                    send(callback, BrokerResult.Failure(RequestStage.Response, FailureCode.Network))
                }
            }
        }
        override fun shutdown() {
            check(Binder.getCallingUid() == applicationInfo.uid)
            handler.post {
                if (profile != null) CookieManager.getInstance().flush()
                page?.view?.destroy(); activity?.finish()
                Process.killProcess(Process.myPid())
            }
        }
    }

    internal fun confirm() { page?.evaluate() }
    internal fun cancel() { page?.finish(BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired)) }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class Page(val job: BrowserJob, private val host: IBrowserHost) {
        val view = WebView(this@NativeSourceBrowserService)
        private val finished = AtomicBoolean()
        private var evaluating = false
        private var navigation = 0
        private var httpError = false

        fun open() {
            CookieManager.getInstance().apply {
                setAcceptCookie(job.cookiesEnabled)
                setAcceptThirdPartyCookies(view, job.cookiesEnabled)
            }
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                blockNetworkLoads = false; allowFileAccess = false; allowContentAccess = false
            }
            view.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                blockNetworkLoads = false
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                job.request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { userAgentString = it.value }
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
                override fun onConsoleMessage(message: ConsoleMessage) = true
            }
            view.setDownloadListener { _, _, _, _, _ -> fail(FailureCode.InvalidRequest) }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Turnstile and ordinary frames use these browser-owned documents.
                    if (!request.isForMainFrame && request.url.toString() in setOf("about:blank", "about:srcdoc")) return false
                    if (request.url.scheme?.lowercase() !in setOf("http", "https")) return true
                    if (request.isForMainFrame && job.options.overrideUrl && matches(request.url.toString())) {
                        completeText(request.url.toString(), view.url ?: job.request.url)
                        return true
                    }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!job.options.overrideUrl && matches(request.url.toString())) handler.post {
                        completeText(request.url.toString(), view.url ?: job.request.url)
                    }
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    navigation++; httpError = false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != view.url) return
                    if (!job.options.interactive || job.options.script.isNotBlank())
                        handler.postDelayed({ evaluate() }, 1000 + job.options.delayMillis)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) httpError = true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(FailureCode.Network)
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); fail(FailureCode.Network)
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail(FailureCode.Network); return true
                }
            }
            view.loadUrl(job.request.url, job.request.headers)
            if (job.options.interactive) startActivity(Intent(this@NativeSourceBrowserService,
                NativeSourceBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private fun matches(url: String) = job.options.sourceRegex.isNotBlank() && Regex(job.options.sourceRegex).containsMatchIn(url)

        fun evaluate() {
            if (finished.get() || evaluating || page !== this) return
            evaluating = true
            val version = navigation
            val script = job.options.script.ifBlank { "document.documentElement.outerHTML" }
            view.evaluateJavascript("""
                (function(){try {
                    var challenge=!!window._cf_chl_opt || /^\/(antibot)(\/|${'$'})/.test(location.pathname) ||
                        /^\s*(Just a moment|人机校验)/i.test(document.title);
                    return JSON.stringify({url:location.href,challenge:challenge,value:eval(${JsonPrimitive(script)})});
                }catch(e){return null;}})()
            """.trimIndent()) { encoded ->
                evaluating = false
                if (finished.get() || page !== this) return@evaluateJavascript
                if (version != navigation) { handler.postDelayed({ evaluate() }, 100); return@evaluateJavascript }
                try {
                    val outer = Json.parseToJsonElement(encoded)
                    val result = if (outer == JsonNull) null else Json.parseToJsonElement(outer.jsonPrimitive.content).jsonObject
                    if (result?.get("challenge")?.jsonPrimitive?.boolean == true) {
                        if (job.options.interactive) handler.postDelayed({ evaluate() }, 1000)
                        else fail(FailureCode.BrowserRequired)
                        return@evaluateJavascript
                    }
                    if (httpError) { fail(FailureCode.Network); return@evaluateJavascript }
                    val value = result?.get("value")
                    if (value == null || value == JsonNull || job.options.sourceRegex.isNotBlank()) {
                        handler.postDelayed({ evaluate() }, 100)
                    } else {
                        val url = result.getValue("url").jsonPrimitive.content
                        if (url != view.url) handler.postDelayed({ evaluate() }, 100)
                        else completeText(if (value is JsonPrimitive) value.content else value.toString(), url)
                    }
                } catch (_: Exception) { fail(FailureCode.Network) }
            }
        }

        private fun completeText(text: String, url: String) {
            if (sourceOrigin(url) == null) { fail(FailureCode.InvalidRequest); return }
            val bytes = text.toByteArray()
            // ByteArray expands to signed decimal values in BrowserWire's bounded JSON.
            if (bytes.size > minOf(job.request.maxResponseBytes ?: 512 * 1024, 1024 * 1024)) {
                fail(FailureCode.ResponseTooLarge); return
            }
            finish(BrokerResult.Success(BrokerResponse(0, url, emptyMap(), bytes, "UTF-8", 0,
                protocol = "", kind = ResponseKind.BrowserDocument)))
        }
        private fun fail(code: FailureCode) = finish(BrokerResult.Failure(RequestStage.Response, code))
        fun finish(result: BrokerResult) {
            if (!finished.compareAndSet(false, true)) return
            view.stopLoading()
            CookieManager.getInstance().flush()
            val currentActivity = activity
            activity = null; page = null
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy(); currentActivity?.finish()
            send(host, result)
        }
    }

    private fun send(host: IBrowserHost, result: BrokerResult) {
        runCatching { BrowserWire.pipe(Json.encodeToString(result)).use { host.complete(it) } }
    }
    override fun onDestroy() { active = null; Process.killProcess(Process.myPid()) }
}
