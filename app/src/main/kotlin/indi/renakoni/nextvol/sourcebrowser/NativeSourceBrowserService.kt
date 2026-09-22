package indi.renakoni.nextvol.sourcebrowser

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import android.webkit.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.CookieManagerCompat
import hnovel.network.*
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.data.web.AndroidSourceNetworks
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Native website execution. Cookie handoff uses trusted Binder callbacks, never a page bridge. */
class NativeSourceBrowserService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var profile: String? = null
    private var networkHandle: Long? = null
    private var networkReady = false
    private var page: Page? = null
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var storageTask: Job? = null
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
                    check(page == null && storageTask == null)
                    initialize(job.profile, job.networkHandle) { openPage(job, callback) }
                } catch (failure: Exception) { failStart(callback, failure) }
            }
        }
        override fun localStorage(payload: ParcelFileDescriptor, callback: IBrowserHost) {
            check(Binder.getCallingUid() == applicationInfo.uid)
            val job = Json.decodeFromString<LocalStorageJob>(BrowserWire.read(payload, NativeBrowserRetention.MAX_BYTES))
            handler.post {
                try {
                    check(page == null && storageTask == null)
                    initialize(job.profile, job.networkHandle) {
                        val task = storageScope.launch(start = CoroutineStart.LAZY) {
                            val result = try {
                                LocalStorageResult(withTimeout(15000) { transferLocalStorage(this@NativeSourceBrowserService, job) })
                            } catch (failure: Exception) {
                                LocalStorageResult(failure = (failure as? LocalStorageFailure)?.code ?: FailureCode.StorageUnavailable)
                            }
                            storageTask = null
                            send(callback, result)
                        }
                        storageTask = task
                        task.start()
                    }
                } catch (failure: Exception) { send(callback, LocalStorageResult(
                    failure = (failure as? RouteFailure)?.code ?: FailureCode.StorageUnavailable)) }
            }
        }
        override fun shutdown() {
            check(Binder.getCallingUid() == applicationInfo.uid)
            handler.post {
                storageScope.cancel()
                if (profile != null) CookieManager.getInstance().flush()
                page?.view?.destroy(); activity?.finish()
                Process.killProcess(Process.myPid())
            }
        }
    }

    private class RouteFailure(val code: FailureCode) : Exception()

    private fun initialize(owner: String, handle: Long?, ready: () -> Unit) {
        if (profile != null) {
            check(profile == owner && networkHandle == handle && networkReady)
            ready()
            return
        }
        bindNetwork(handle)
        NativeBrowserFiles(this).initialize(owner)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        profile = owner
        networkHandle = handle
        if (handle != null) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) throw RouteFailure(FailureCode.RouteUnsupported)
            // Binding covers sockets/DNS. The direct override also excludes a system HTTP proxy.
            ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addDirect().build(),
                { handler.post(it) }) { networkReady = true; ready() }
        } else { networkReady = true; ready() }
    }

    /** Called only in this dedicated process, before any Chromium initialization. */
    private fun bindNetwork(handle: Long?) {
        if (handle == null) return
        if (Build.VERSION.SDK_INT < 28) throw RouteFailure(FailureCode.RouteUnsupported)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = Network.fromNetworkHandle(handle)
        if (connectivity.getNetworkCapabilities(network)?.let(AndroidSourceNetworks::eligible) != true ||
            !connectivity.bindProcessToNetwork(network)) throw RouteFailure(FailureCode.RouteUnavailable)
    }

    private fun openPage(job: BrowserJob, callback: IBrowserHost) {
        try {
            check(networkReady && profile == job.profile && networkHandle == job.networkHandle && page == null)
            active = this
            page = Page(job, callback).also { it.open() }
        } catch (failure: Exception) { failStart(callback, failure) }
    }

    private fun failStart(callback: IBrowserHost, failure: Exception) {
        page?.view?.destroy(); page = null
        send(callback, BrokerResult.Failure(RequestStage.Connect,
            if (failure is RouteFailure) failure.code else FailureCode.Network))
    }

    internal fun confirm() { page?.evaluate() }
    internal fun cancel() { page?.finish(BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired)) }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class Page(val job: BrowserJob, private val host: IBrowserHost) {
        val view = WebView(this@NativeSourceBrowserService)
        private val finished = AtomicBoolean()
        private var evaluating = false
        private var navigation = 0
        @Volatile private var httpError = false
        @Volatile private var httpChallenge: BrowserChallengeKind? = null

        fun open() {
            // WebView must re-check each new TLS handshake against this account's current exceptions.
            view.clearSslPreferences()
            CookieManager.getInstance().apply {
                // Legado's enabledCookieJar controls automatic HTTP cookie capture,
                // not the browser session required by login and human verification.
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
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
                job.request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { applySourceUserAgent(it.value) }
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
                    // Some WebViews deliver the response error before onPageStarted.
                    // Reset at request dispatch so onPageStarted cannot erase a challenge.
                    if (request.isForMainFrame) { httpError = false; httpChallenge = null }
                    if (!job.options.overrideUrl && matches(request.url.toString())) handler.post {
                        completeText(request.url.toString(), view.url ?: job.request.url)
                    }
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    navigation++
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != view.url) return
                    if (!job.options.interactive || job.options.script.isNotBlank())
                        handler.postDelayed({ evaluate() }, 1000 + job.options.delayMillis)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) {
                        httpError = true
                        if (response.responseHeaders?.entries?.any { it.key.equals("cf-mitigated", true) && it.value == "challenge" } == true)
                            httpChallenge = BrowserChallengeKind.Cloudflare
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(FailureCode.Network)
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    val problem = nativeCertificateProblem(error)
                    if (problem != null && job.certificates.any { it.origin == problem.origin && it.fingerprint == problem.fingerprint }) {
                        handler.proceed()
                    } else {
                        handler.cancel()
                        finish(BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, certificate = problem))
                    }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail(FailureCode.Network); return true
                }
            }
            // A detached WebView has no layout pass. Give it a real rendering area
            // before navigation; the foreground Activity later lays it out normally.
            val display = resources.displayMetrics
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(display.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(display.heightPixels, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            fun navigate() {
                if (finished.get()) return
                view.loadUrl(job.request.url, job.request.headers)
                if (job.options.interactive) startActivity(Intent(this@NativeSourceBrowserService,
                    NativeSourceBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            require(job.cookies.size <= 256 && job.cookies.sumOf(String::length) <= 65536)
            var pending = job.cookies.size
            if (pending == 0) navigate()
            else job.cookies.forEach { cookie ->
                CookieManager.getInstance().setCookie(job.request.url, cookie) { accepted ->
                    if (!accepted) fail(FailureCode.StorageUnavailable)
                    if (--pending == 0) navigate()
                }
            }
        }

        private fun matches(url: String) = job.options.sourceRegex.isNotBlank() && Regex(job.options.sourceRegex).containsMatchIn(url)

        fun evaluate() {
            if (finished.get() || evaluating || page !== this) return
            evaluating = true
            val version = navigation
            val script = job.options.script.ifBlank { "document.documentElement.outerHTML" }
            view.evaluateJavascript("""
                (function(){try {
                    var challenge=window._cf_chl_opt || /^\s*Just a moment/i.test(document.title) ? 'Cloudflare' :
                        /^\/WAF\/VERIFY\/CAPTCHA/i.test(location.pathname) &&
                        (/^\s*Verify Yourself\s*${'$'}/i.test(document.title) || document.querySelector('form#ui-form')) ? 'SiteVerification' :
                        /^\/antibot(\/|${'$'})/.test(location.pathname) || /^\s*人机校验/.test(document.title) ||
                        document.querySelector('form#J_ManMachineVerify') ? 'SiteVerification' :
                        /^\/login(\/|${'$'})/.test(location.pathname) && document.querySelector('input[type=password]') ? 'Login' : null;
                    return JSON.stringify({url:location.href,challenge:challenge,value:challenge ? null : eval(${JsonPrimitive(script)})});
                }catch(e){return null;}})()
            """.trimIndent()) { encoded ->
                evaluating = false
                if (finished.get() || page !== this) return@evaluateJavascript
                if (version != navigation) { handler.postDelayed({ evaluate() }, 100); return@evaluateJavascript }
                try {
                    val outer = Json.parseToJsonElement(encoded)
                    val result = if (outer == JsonNull) null else Json.parseToJsonElement(outer.jsonPrimitive.content).jsonObject
                    val challenge = result?.get("challenge")?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: httpChallenge?.name
                    if (challenge != null) {
                        if (job.options.interactive) handler.postDelayed({ evaluate() }, 1000)
                        else finish(BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired,
                            challenge = BrowserChallengeKind.valueOf(challenge)))
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
            if (bytes.size > minOf(job.request.maxResponseBytes ?: BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES,
                    BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES)) {
                fail(FailureCode.ResponseTooLarge); return
            }
            finish(BrokerResult.Success(BrokerResponse(0, url, emptyMap(), bytes, "UTF-8", 0,
                protocol = "", kind = ResponseKind.BrowserDocument)))
        }
        private fun fail(code: FailureCode) = finish(BrokerResult.Failure(RequestStage.Response, code))
        private fun cookies(url: String): NativeCookieSnapshot {
            val manager = CookieManager.getInstance()
            val parsed = checkNotNull(url.toHttpUrlOrNull())
            val completeMetadata = WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)
            val values = if (completeMetadata) {
                CookieManagerCompat.getCookieInfo(manager, url).filterNot { value ->
                    value.split(';').any { it.trim().equals("Partitioned", true) }
                }
            } else {
                // Older WebViews expose only a request header. Keep this handoff in memory,
                // host-only and no broader than the queried path; never invent an expiry.
                val root = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                val rootCookies = manager.getCookie(root).orEmpty().split(';').map(String::trim).toSet()
                manager.getCookie(url).orEmpty().split(';').mapNotNull { pair ->
                    val parts = pair.trim().split('=', limit = 2)
                    if (parts.size != 2) null else Cookie.Builder().name(parts[0]).value(parts[1])
                        .hostOnlyDomain(parsed.host).path(if (pair.trim() in rootCookies) "/" else parsed.encodedPath).httpOnly()
                        .apply { if (parsed.isHttps) secure() }.build().toString()
                }
            }
            require(values.size <= 256 && values.sumOf(String::length) <= 65536)
            return NativeCookieSnapshot(url, values, completeMetadata)
        }

        fun finish(result: BrokerResult) {
            if (!finished.compareAndSet(false, true)) return
            view.stopLoading()
            CookieManager.getInstance().flush()
            val completed = if (result is BrokerResult.Success) try {
                val urls = listOf(job.request.url, result.response.finalUrl).mapNotNull { it.toHttpUrlOrNull() }
                    .flatMap { listOf(it.newBuilder().encodedPath("/").query(null).fragment(null).build().toString(),
                        it.newBuilder().query(null).fragment(null).build().toString()) }.distinct()
                BrowserWire.read(host.call("cookies", Json.encodeToString(urls.map(::cookies))))
                result
            } catch (_: Exception) { BrokerResult.Failure(RequestStage.Storage, FailureCode.StorageUnavailable) }
            else result
            val currentActivity = activity
            activity = null; page = null
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy(); currentActivity?.finish()
            send(host, completed)
        }
    }

    private fun send(host: IBrowserHost, result: BrokerResult) {
        runCatching { BrowserWire.pipeResult(result).use { host.complete(it) } }
    }
    private fun send(host: IBrowserHost, result: LocalStorageResult) {
        runCatching { BrowserWire.pipe(Json.encodeToString(result)).use { host.complete(it) } }
    }
    override fun onDestroy() { storageScope.cancel(); active = null; Process.killProcess(Process.myPid()) }
}
