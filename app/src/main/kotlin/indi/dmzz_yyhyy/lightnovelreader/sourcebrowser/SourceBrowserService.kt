package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.*
import android.webkit.*
import hnovel.network.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Disposable browser. Chromium has no native network path; every admitted request goes to the broker. */
class SourceBrowserService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean()
    private lateinit var job: BrowserJob
    private lateinit var host: IBrowserHost
    private lateinit var bootstrap: String
    internal var webView: WebView? = null
    internal var activity: SourceBrowserActivity? = null
    @Volatile private var mainResponse: BrokerResponse? = null
    @Volatile private var mainUrl = ""
    private var firstRequest = true
    private var attempts = 0

    companion object {
        internal var active: SourceBrowserService? = null
        private const val CSP = "default-src http: https: data: blob:; script-src http: https: 'unsafe-inline' 'unsafe-eval'; connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'; base-uri 'self'"
    }

    override fun onCreate() {
        super.onCreate()
        // The main app has no WebViews. On API 24-27 this one process exclusively owns app_webview.
        val name = if (Build.VERSION.SDK_INT >= 28) "webview_source_browser" else "webview"
        val directory = getDir(name, MODE_PRIVATE)
        check(directory.canonicalFile.parentFile == File(applicationInfo.dataDir).canonicalFile)
        check(directory.deleteRecursively())
        if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("source_browser")
        bootstrap = assets.open("source-browser.js").bufferedReader().use { it.readText() }
    }

    override fun onBind(intent: Intent): IBinder = object : IBrowserService.Stub() {
        override fun start(payload: String, callback: IBrowserHost) {
            check(Binder.getCallingUid() == applicationInfo.uid && payload.length <= 393216)
            check(!::job.isInitialized)
            job = Json.decodeFromString(payload); host = callback
            handler.post { runCatching { open() }.onFailure { fail() } }
        }
        override fun shutdown() { check(Binder.getCallingUid() == applicationInfo.uid); Process.killProcess(Process.myPid()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open() {
        active = this
        CookieManager.getInstance().setAcceptCookie(false)
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.blockNetworkLoads = true
            serviceWorkerWebSettings.allowContentAccess = false
            serviceWorkerWebSettings.allowFileAccess = false
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = denied()
            })
        }
        val view = WebView(this)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        view.addJavascriptInterface(PageBridge(), "SourceBrowser")
        view.setDownloadListener { _, _, _, _, _ -> fail() }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onConsoleMessage(message: ConsoleMessage) = true // Page text can contain secrets.
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = intercept(request)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = request.url.scheme?.lowercase() in setOf("http", "https")
                if (!allowed) fail()
                if (allowed && job.options.overrideUrl && matches(request.url.toString())) {
                    handler.post { completeText(request.url.toString()) }; return true
                }
                return !allowed
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!job.options.interactive && url == mainUrl && redirected == null) handler.postDelayed({ evaluate() }, job.options.delayMillis)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && redirected == null) fail()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail(); return true }
        }
        view.loadUrl(job.request.url)
        if (job.options.interactive) startActivity(Intent(this, SourceBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun rpc(operation: String, args: JsonObject): String {
        check(!finished.get())
        return BrowserWire.read(host.call(operation, args.toString()))
    }
    private fun request(request: BrokerRequest): BrokerResponse {
        val result = Json.decodeFromString<BrokerResult>(rpc("request", buildJsonObject {
            put("url", request.url); put("method", request.method)
            put("headers", JsonObject(request.headers.mapValues { JsonPrimitive(it.value) }))
            request.body?.let { put("body", it) }
        }))
        check(result is BrokerResult.Success)
        return result.response
    }

    private fun intercept(incoming: WebResourceRequest): WebResourceResponse = try {
        check(!finished.get() && incoming.url.scheme?.lowercase() in setOf("http", "https"))
        val initial = incoming.isForMainFrame && firstRequest
        if (initial) firstRequest = false
        val response = if (initial && job.options.html != null) BrokerResponse(200, job.request.url,
            mapOf("Content-Type" to listOf("text/html; charset=UTF-8")), job.options.html!!.toByteArray(), "UTF-8", 0)
        else redirected?.takeIf { incoming.isForMainFrame && it.finalUrl == incoming.url.toString() }?.also { redirected = null }
        ?: request(if (initial) job.request else BrokerRequest("browser", incoming.url.toString(),
            method = incoming.method.also { check(it == "GET" || it == "HEAD") }, headers = incoming.requestHeaders))
        if (!job.options.overrideUrl && matches(response.finalUrl)) handler.post { completeText(response.finalUrl) }
        val type = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()
            ?.substringBefore(';') ?: if (incoming.isForMainFrame) "text/html" else "application/octet-stream"
        var body = response.body
        if (incoming.isForMainFrame) {
            check(type == "text/html" || type == "application/xhtml+xml")
            mainResponse = response; mainUrl = response.finalUrl
            if (response.finalUrl != incoming.url.toString()) {
                // Broker followed and authorized every redirect. Load its final origin so DOM relative URLs agree.
                redirected = response
                handler.post { webView?.loadUrl(response.finalUrl) }
                return denied()
            }
            val storage = rpc("storage", buildJsonObject { put("url", mainUrl) })
            val doc = org.jsoup.Jsoup.parse(response.text(), mainUrl)
            val script = "(function(){var initial=${storage.replace("<", "\\u003c")};Object.keys(initial).forEach(function(k){localStorage.setItem(k,initial[k]);});})();\n" + bootstrap
            doc.head().prependElement("script").appendChild(org.jsoup.nodes.DataNode(script))
            body = doc.outerHtml().toByteArray()
        }
        val headers = response.headers.filterKeys { it.lowercase() !in setOf("set-cookie", "content-encoding", "content-length", "content-security-policy") }
            .mapValues { it.value.joinToString(", ") }.toMutableMap()
        headers["Content-Security-Policy"] = CSP
        WebResourceResponse(type, if (incoming.isForMainFrame) "UTF-8" else response.charset,
            response.status.takeIf { it in 200..299 || it in 400..599 } ?: 502, "Response", headers, ByteArrayInputStream(body))
    } catch (_: Exception) { if (incoming.isForMainFrame) fail(); denied() }

    @Volatile private var redirected: BrokerResponse? = null

    private fun matches(url: String): Boolean = job.options.sourceRegex.isNotBlank() && Regex(job.options.sourceRegex).containsMatchIn(url)

    internal fun evaluate() {
        if (finished.get()) return
        val script = job.options.script.ifBlank { "document.documentElement.outerHTML" }
        webView?.evaluateJavascript("(function(){try {var value=eval(${JsonPrimitive(script)});var state={};for(var i=0;i<localStorage.length;i++){var key=localStorage.key(i);state[key]=localStorage.getItem(key);}SourceBrowser.call('storage',JSON.stringify({url:location.href,value:state}));return JSON.stringify({value:value});}catch(e){return '{}';}})()") { result ->
            try {
                val encoded = Json.parseToJsonElement(result).jsonPrimitive.content
                val value = Json.parseToJsonElement(encoded).jsonObject["value"]
                if (value == null || value == JsonNull || job.options.sourceRegex.isNotBlank()) {
                    if (++attempts >= 30) fail() else handler.postDelayed({ evaluate() }, 100)
                } else completeText(if (value is JsonPrimitive) value.content else value.toString())
            } catch (_: Exception) { fail() }
        }
    }

    private fun completeText(value: String) {
        if (value.toByteArray().size > (job.request.maxResponseBytes ?: 512 * 1024)) { fail(); return }
        val original = mainResponse ?: return
        complete(BrokerResult.Success(original.copy(body = value.toByteArray(), charset = "UTF-8", finalUrl = mainUrl)))
    }
    internal fun fail() { complete(BrokerResult.Failure(RequestStage.Response, FailureCode.Network)) }
    private fun complete(result: BrokerResult) {
        if (!finished.compareAndSet(false, true)) return
        handler.post {
            webView?.stopLoading(); activity?.finish()
            runCatching { BrowserWire.pipe(Json.encodeToString(result)).use { host.complete(it) } }
        }
    }
    override fun onDestroy() { active = null; Process.killProcess(Process.myPid()) }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private inner class PageBridge {
        @JavascriptInterface fun call(operation: String, arguments: String): String = runCatching {
            require(operation in setOf("request", "navigate", "cookie", "storage") && arguments.length <= 65536)
            val args = Json.parseToJsonElement(arguments).jsonObject
            if (operation !in setOf("request", "navigate")) return@runCatching rpc(operation, args)
            val result = Json.decodeFromString<BrokerResult>(rpc("request", args))
            check(result is BrokerResult.Success)
            if (operation == "navigate") {
                redirected = result.response
                handler.post { webView?.loadUrl(result.response.finalUrl) }
            }
            if (!job.options.overrideUrl && matches(result.response.finalUrl)) handler.post { completeText(result.response.finalUrl) }
            buildJsonObject {
                put("status", result.response.status); put("url", result.response.finalUrl); put("body", result.response.text())
                put("bytes", android.util.Base64.encodeToString(result.response.body, android.util.Base64.NO_WRAP))
                put("headers", JsonObject(result.response.headers.filterKeys { !it.equals("Set-Cookie", true) }
                    .mapValues { JsonPrimitive(it.value.joinToString(", ")) }))
            }.toString()
        }.getOrElse { "null" }
    }
}
