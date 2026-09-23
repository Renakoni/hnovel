package hnovel.network

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/** Host-owned authority. A script receives a bound session protocol, never open() or a raw client. */
class SourceBroker(private val storageRoot: Path, private val dns: Dns = VpnDns.Default,
    private val limits: BrokerLimits = BrokerLimits(), private val cipher: StorageCipher = StorageCipher.Plain,
    private val browser: BrowserExecutor? = null, route: SourceRouteProvider? = null) : AutoCloseable {
    private val routes = route ?: SourceNetworkRoute(SourceNetworkMode.SystemDefault, dns).let { fallback ->
        SourceRouteProvider { fallback }
    }
    private val sessions = mutableMapOf<List<String>, SourceSession>()
    @Synchronized fun open(scope: SourceScope, grants: List<NetworkGrant>): SourceSession {
        val key = scope.components(account = false)
        val old = sessions[key]
        if (old != null && old.scope == scope && !old.closed) {
            require(old.grants == grants) { "Close the session before changing its grants" }
            return old
        }
        old?.close()
        return SourceSession(scope, grants, storageRoot, dns, limits, cipher, browser, routes).also {
            if (old != null) it.inheritCaches(old)
            sessions[key] = it
        }
    }
    @Synchronized override fun close() { sessions.values.forEach { it.close() }; sessions.clear() }
}

class SourceSession internal constructor(val scope: SourceScope, grants: List<NetworkGrant>, root: Path,
    dns: Dns, private val limits: BrokerLimits, cipher: StorageCipher = StorageCipher.Plain,
    private val browser: BrowserExecutor? = null, private val routes: SourceRouteProvider) : AutoCloseable {
    internal val grants = grants.map { it.copy(headers = it.headers.toMap()) }
    private val policy = NetworkPolicy(this.grants, dns)
    private val imagePolicy = NetworkPolicy(this.grants, dns, publicImages = true)
    private val denied = linkedSetOf<OriginDenial>()
    /** Ephemeral, bounded and source/account owned; revision replacement does not inherit these requests. */
    val deniedOrigins: List<OriginDenial> get() = synchronized(this) { denied.toList() }
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(limits.concurrency)
    private val rate = Mutex()
    private var lastStart = 0L
    @Volatile private var sourcePacing = SourceRequestPacer(null)
    private var cache = ResponseCache(limits)
    private val valuesCache = ValueCache(limits, SourceStorage(root, scope.components(false) + "cache",
        limits.copy(maxStorageBytes = limits.maxCacheBytes.toLong()), cipher))
    private val config = SourceStorage(root, scope.components(false) + "config", limits, cipher)
    private val bookState by lazy { SourceStorage(root, scope.components(false) + "books",
        limits.copy(maxStorageBytes = limits.maxBookStorageBytes, maxStorageEntries = limits.maxBookStorageEntries), cipher) }
    private val account = SourceStorage(root, scope.components(true) + "account", limits, cipher)
    private val cookieStorage = SourceStorage(root, scope.components(true) + "cookies", limits, cipher)
    private val cookies = SourceCookies(cookieStorage)
    private val certificates = SourceCertificates(SourceStorage(root, scope.components(true) + "certificate-exceptions", limits, cipher))
    @Volatile var enabledCookieJar = true
    var sourceUrl: String = ""
        private set
    var browserRead: Boolean = false
        private set
    var localStorageRetention = LocalStorageRetention()
        private set
    private var defaultUserAgent: String? = null
    @Synchronized fun configureSource(url: String, cookiesEnabled: Boolean, browserRead: Boolean = false,
        concurrentRate: String? = null, localStorageRetention: LocalStorageRetention = LocalStorageRetention(),
        defaultUserAgent: String? = null) {
        require(sourceUrl.isEmpty() || sourceUrl == url)
        sourceUrl = url
        enabledCookieJar = cookiesEnabled
        this.browserRead = browserRead
        this.localStorageRetention = localStorageRetention.approved(grants)
        this.defaultUserAgent = defaultUserAgent
        val parsedRate = SourceRequestRate.parse(concurrentRate)
        if (sourcePacing.rate != parsedRate) sourcePacing = SourceRequestPacer(parsedRate)
    }

    /** Host-only: call inside browser serialization, immediately before starting a navigation.
     * The enclosing execute owns cancellation and timeout; browser subresources never call this. */
    suspend fun awaitBrowserAdmission() {
        checkOpen()
        sourcePacing.awaitAdmission()
        currentCoroutineContext().ensureActive()
        checkOpen()
    }
    // An HTTP proxy would move DNS/peer validation to an unchecked destination.
    // Each route below supplies its own DNS, sockets and connection pool.
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .addNetworkInterceptor { chain ->
            val peer = chain.connection()?.socket()?.remoteSocketAddress as? InetSocketAddress
                ?: throw BrokerFailure(RequestStage.Permission, FailureCode.AddressDenied)
            (chain.request().tag(NetworkPolicy::class.java) ?: policy).checkPeer(chain.request().url, peer.address)
            chain.proceed(chain.request())
        }.build()
    private val routeClients = mutableMapOf<Pair<SourceNetworkRoute, String>, OkHttpClient>()

    @Synchronized private fun clientFor(route: SourceNetworkRoute, url: HttpUrl): OkHttpClient {
        checkOpen()
        route.checkAvailable()
        routeClients.entries.removeAll { (network, transport) ->
            (!network.first.available).also { if (it) network.first.detach(transport.connectionPool) }
        }
        val origin = NetworkPolicy.origin(url)
        if ((route to origin) !in routeClients && routeClients.size >= 64) {
            // Public cover URLs can introduce more origins than the source's explicit grants.
            val idle = routeClients.entries.firstOrNull {
                it.value.connectionPool.connectionCount() == it.value.connectionPool.idleConnectionCount()
            }
            if (idle != null) { idle.key.first.detach(idle.value.connectionPool); routeClients.remove(idle.key) }
        }
        return routeClients.getOrPut(route to origin) {
            val pool = ConnectionPool()
            val builder = client.newBuilder().socketFactory(route.socketFactory).connectionPool(pool)
            val transport = (if (url.isHttps) certificates.configure(builder, origin) else builder).build()
            route.attach(pool)
            transport
        }
    }
    val closed get() = !lifetime.isActive

    /** Host-only: native background work must stop even after its last document completed. */
    fun onClosed(action: () -> Unit): AutoCloseable {
        val subscription = lifetime.coroutineContext.job.invokeOnCompletion { action() }
        return AutoCloseable { subscription.dispose() }
    }

    @Synchronized fun certificateExceptions(): List<CertificateExceptionSite> { checkOpen(); return certificates.exceptions() }

    /** Host UI only; accepts only a certificate observed by this still-current session. */
    @Synchronized fun approveCertificate(problem: CertificateProblem) {
        checkOpen()
        certificates.approve(problem)
        routeClients.entries.removeAll { (key, transport) ->
            (key.second == problem.origin).also { if (it) key.first.detach(transport.connectionPool) }
        }
    }

    /** Retires HTTP pools and in-flight commits; onClosed also stops the native browser. */
    @Synchronized fun revokeCertificate(origin: String) {
        checkOpen()
        close()
        cache.clear()
        certificates.revoke(origin)
    }

    /** Trusted native browser callback. Unknown origins still need their ordinary network grant. */
    @Synchronized fun browserCertificateFailure(problem: CertificateProblem): BrokerResult.Failure {
        checkOpen()
        permissionFailureDetail(problem.origin)?.let { return it }
        certificates.remember(problem)
        return BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, certificate = problem)
    }

    suspend fun webViewUserAgent(): String {
        checkOpen()
        val value = browser?.defaultUserAgent()
        checkOpen()
        check(!value.isNullOrBlank()) { "WebView user agent unavailable" }
        return value
    }

    suspend fun showMessage(message: String, long: Boolean, guard: RequestCommitGuard) {
        guard.commit { checkOpen() }
        browser?.showMessage(message, long, RequestCommitGuard { action -> guard.commit { checkOpen(); action() } })
    }

    /** Host-only revision/re-enable handoff. A closed session may retain memory-only cookies;
     * exact scope equality still forbids transfer across sources, profiles or account generations. */
    fun inheritCookies(previous: SourceSession) {
        require(scope == previous.scope)
        checkOpen()
        cookies.inherit(previous.cookies)
    }

    /** Retain source caches when credentials or a runtime binding change. */
    @Synchronized fun inheritCaches(previous: SourceSession) {
        require(scope.components(false) == previous.scope.components(false))
        checkOpen()
        cache = previous.cache
    }

    @Synchronized fun cookie(url: String): String { checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed); return cookies.header(parsed, null) }
    @Synchronized fun setCookie(url: String, value: String, replace: Boolean = false) {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL"); policy.check(parsed)
        cookies.setHeader(parsed, value, replace)
    }
    @Synchronized fun removeCookie(url: String) { checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed); cookies.setHeader(parsed, "", true) }

    /** Host-only cookie handoff; never exposed as a website JavascriptInterface. */
    @Synchronized fun nativeBrowserCookies(url: String): List<String> {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed)
        return cookies.browserSnapshot(parsed)
    }

    @Synchronized fun updateNativeBrowserCookies(url: String, values: List<String>, completeMetadata: Boolean = true) {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed)
        cookies.replaceBrowserSnapshot(parsed, values, completeMetadata)
    }

    @Synchronized fun browserCookie(url: String, value: String? = null): String {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL"); policy.check(parsed)
        if (!enabledCookieJar) return ""
        if (value != null) cookies.documentCookie(parsed, value)
        return cookies.documentHeader(parsed)
    }

    /** Called by the host after revocation; deletes only the retired account's sensitive state. */
    fun clearAccount() {
        val storageFailure = synchronized(this) {
            close()
            val accountCleared = account.clear() is StorageResult.Value
            val cookiesCleared = cookieStorage.clear() is StorageResult.Value
            val certificatesCleared = certificates.clear() is StorageResult.Value
            cookies.restoreMemory(emptyList())
            if (accountCleared && cookiesCleared && certificatesCleared) null else IllegalStateException("Account cleanup failed")
        }
        // Browser cancellation may finish on another thread that checks this session.
        // Do not hold the session monitor while waiting for its process to stop.
        try { browser?.clearAccount(scope, localStorageRetention) }
        catch (failure: Exception) {
            if (storageFailure == null) throw failure
            storageFailure.addSuppressed(failure)
        }
        storageFailure?.let { throw it }
    }

    @Synchronized fun read(request: StorageRequest): StorageResult {
        checkOpen()
        return when (request.area) {
            StorageArea.Config -> config.read(request.key)
            StorageArea.Account -> account.read(request.key)
            StorageArea.Cache -> valuesCache.read(request.key)
            StorageArea.BookState -> bookState.read(request.key)
        }
    }
    @Synchronized fun write(request: StorageRequest): StorageResult {
        checkOpen()
        return when (request.area) {
            StorageArea.Config -> config.write(request.key, request.value)
            StorageArea.Account -> account.write(request.key, request.value)
            StorageArea.Cache -> valuesCache.write(request)
            StorageArea.BookState -> bookState.write(request.key, request.value)
        }
    }
    @Synchronized fun writeBookStates(values: Map<String, String>): StorageResult {
        checkOpen()
        return bookState.writeAll(values)
    }
    fun newVariables(initial: Map<String, String> = emptyMap()) = RequestVariables(initial)

    /** Source/profile-scoped installation pseudonym. Never reads the global Android device ID. */
    @Synchronized fun installationIdentifier(): StorageResult {
        checkOpen()
        val key = "installation-identifier"
        val current = config.read(key)
        if (current !is StorageResult.Value) return current
        if (current.value != null) return if (current.value.matches(Regex("[0-9a-f]{16}"))) current
            else StorageResult.Failure(FailureCode.StorageUnavailable)
        val bytes = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        val identifier = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        return config.write(key, identifier)
    }

    /** Checks current grants for a cached resource without opening a connection. */
    fun permissionFailure(url: String): FailureCode? = permissionFailureDetail(url)?.code

    fun permissionFailureDetail(url: String, kind: ResourceKind = ResourceKind.Document): BrokerResult.Failure? {
        checkOpen()
        val parsed = url.toHttpUrlOrNull() ?: return BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest)
        return try { policy.check(parsed); null }
        catch (failure: BrokerFailure) { permissionResult(failure, kind) }
    }

    @Synchronized private fun permissionResult(failure: BrokerFailure, kind: ResourceKind): BrokerResult.Failure {
        checkOpen()
        val detail = failure.deniedOrigin?.let { OriginDenial(it, kind) }
        if (detail != null && denied.size < 32) denied.add(detail)
        return BrokerResult.Failure(failure.stage, failure.code, denial = detail)
    }

    suspend fun execute(request: BrokerRequest, guard: RequestCommitGuard = RequestCommitGuard { it() }): BrokerResult =
        execute(request, guard, policy)

    /** Host-only HTTP transport for synthetic/image verification documents; avoids browser re-entry. */
    suspend fun executeHttp(request: BrokerRequest, guard: RequestCommitGuard, route: SourceNetworkRoute? = null): BrokerResult {
        require(request.browser == null)
        return execute(request, guard, policy, browserDefault = false, paceSource = false, route = route)
    }

    /** Host-only image loading for an URL already extracted from a book. No script bridge exposes this. */
    suspend fun loadImage(request: BrokerRequest, guard: RequestCommitGuard = RequestCommitGuard { it() }): BrokerResult {
        require(request.kind == ResourceKind.Image && request.method == "GET" && request.body == null && request.browser == null)
        // Coil owns image caching; keep downloaded images out of the script response cache.
        return execute(request.copy(cache = CacheMode.Disabled), guard, imagePolicy, paceSource = false)
    }

    private suspend fun execute(request: BrokerRequest, guard: RequestCommitGuard, policy: NetworkPolicy,
        browserDefault: Boolean = true, paceSource: Boolean = true, route: SourceNetworkRoute? = null): BrokerResult {
        checkOpen()
        val selectedRoute = runCatching { route ?: routes.snapshot() }
        // A browser source keeps document reads in the same native session. Binary/image
        // requests stay explicit HTTP operations; URL options can still supply a render script.
        val snapshot = request.copy(headers = request.headers.toMap(), browser = request.browser ?:
            if (browserDefault && browserRead && request.kind == ResourceKind.Document) BrowserOptions() else null)
        val work = lifetime.async {
            var stage = RequestStage.Queue
            try {
                val transport = selectedRoute.getOrThrow()
                validate(snapshot)
                withTimeout(if (snapshot.browser?.interactive == true) 300000 else snapshot.timeoutMillis) {
                    if (snapshot.url.startsWith("data:")) {
                        val encoded = Regex("^data:.*?;base64,([A-Za-z0-9+/=\\s]*)$").matchEntire(snapshot.url)
                            ?.groupValues?.get(1) ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
                        val bytes = java.util.Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
                        if (bytes.size > minOf(snapshot.maxResponseBytes ?: limits.maxResponseBytes, limits.maxResponseBytes))
                            throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                        guard.commit { checkOpen() }
                        // Legado StrResponse falls back to this URL for non-HTTP input; no socket is opened.
                        BrokerResult.Success(BrokerResponse(200, "http://localhost/", emptyMap(), bytes, snapshot.charset, 0,
                            message = "OK", protocol = "data"))
                    } else if (snapshot.browser != null) {
                        val url = snapshot.url.toHttpUrlOrNull() ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
                        val browserHeaders = if (snapshot.browser.webCookie != null) {
                            policy.check(url)
                            emptyMap()
                        } else headers(url, snapshot.headers, policy, includeCookies = false).toMap()
                        val maxBytes = minOf(snapshot.maxResponseBytes ?: limits.maxResponseBytes, limits.maxResponseBytes)
                        val result = browser?.execute(this@SourceSession, snapshot.copy(browser = null, headers = browserHeaders,
                            maxResponseBytes = maxBytes), snapshot.browser, guard, transport)
                            ?: BrokerResult.Failure(RequestStage.Parse, FailureCode.BrowserRequired)
                        if (result is BrokerResult.Success && result.response.body.size > maxBytes)
                            BrokerResult.Failure(RequestStage.Response, FailureCode.ResponseTooLarge) else result
                    } else permits.withPermit { stage = RequestStage.Connect; perform(snapshot, guard, policy, paceSource, transport, browserDefault) }
                }
            } catch (_: TimeoutCancellationException) {
                BrokerResult.Failure(stage, FailureCode.Timeout)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: BrokerFailure) {
                  var result: BrokerResult.Failure? = null
                  guard.commit { result = permissionResult(failure, snapshot.kind) }
                  checkNotNull(result)
              }
              catch (_: IllegalArgumentException) { BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest) }
              catch (_: java.net.UnknownHostException) { BrokerResult.Failure(RequestStage.Connect, FailureCode.Dns) }
              catch (_: IOException) { BrokerResult.Failure(RequestStage.Connect, FailureCode.Network) }
        }
        return try { work.await().also { checkOpen() }.let { result ->
            if (result is BrokerResult.Success && snapshot.responseAsHex) result.copy(response = result.response.copy(textAsHex = true)) else result
        } } finally { work.cancel() }
    }

    private fun validate(request: BrokerRequest) {
        if (request.id.length > 256 || request.url.length + request.headers.entries.sumOf { it.key.length + it.value.length } > 65536 ||
            request.method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") || request.retry !in 0..limits.maxRetry ||
            request.timeoutMillis !in 1..limits.maxTimeoutMillis || (request.body?.length ?: 0) > limits.maxRequestBytes ||
            request.maxResponseBytes?.let { it <= 0 } == true ||
            request.method in setOf("GET", "HEAD") && request.body != null ||
            request.cache != CacheMode.Disabled && request.method != "GET") {
            throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
        }
        Charset.forName(request.charset)
        request.responseCharset?.let(Charset::forName)
        if (request.headers.keys.any { it.lowercase() in setOf("host", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection") }) {
            throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
        }
    }

    private suspend fun perform(request: BrokerRequest, guard: RequestCommitGuard, policy: NetworkPolicy,
        paceSource: Boolean, route: SourceNetworkRoute, detectChallenges: Boolean): BrokerResult {
        val initialUrl = request.url.toHttpUrlOrNull() ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
        policy.check(initialUrl)
        val initialHeaders = headers(initialUrl, request.headers, policy)
        val cacheKey = hash(Json.encodeToString(listOf(initialUrl.toString(), request.responseCharset.orEmpty(), request.followRedirects.toString(),
            Json.encodeToString<Map<String, List<String>>>(initialHeaders.toMultimap().mapKeys { it.key.lowercase() }.toSortedMap()))))
        if (request.cache != CacheMode.Disabled) {
            cache.get(cacheKey)?.let {
                if (it.body.size > (request.maxResponseBytes ?: limits.maxResponseBytes))
                    return BrokerResult.Failure(RequestStage.Response, FailureCode.ResponseTooLarge)
                // A previously cached verification page is not a readable document.
                // Raw API callers still receive their original response unchanged.
                if (!detectChallenges || request.kind != ResourceKind.Document || websiteChallenge(it) == null)
                    return BrokerResult.Success(it)
            }
            if (request.cache == CacheMode.Only) return BrokerResult.Failure(RequestStage.Response, FailureCode.CacheMiss)
        }
        for (attempt in 0..request.retry) {
            try {
                var response = redirects(request, initialUrl, guard, policy, paceSource, route)
                // Raw java.ajax/connect responses and browser subrequests must remain available
                // to source login/check scripts. Detect challenges before document extraction.
                if (detectChallenges && request.kind == ResourceKind.Document) {
                    // Preserve the existing one-shot cookie bootstrap for GET documents.
                    // POST must reach verification without silently replaying its body.
                    if (request.method == "GET" && isCookieRefreshChallenge(response))
                        response = redirects(request, initialUrl, guard, policy, paceSource, route)
                    websiteChallenge(response)?.let { challenge ->
                        return BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired, attempt,
                            challenge = challenge, verificationRequest = request)
                    }
                }
                if (response.status in setOf(429, 502, 503, 504) && attempt < request.retry) continue
                if (request.cache == CacheMode.ReadThrough && response.status in 200..299) guard.commit {
                    cache.put(cacheKey, response)
                }
                return BrokerResult.Success(response)
            } catch (failure: BrokerFailure) { throw failure }
              catch (failure: IOException) {
                route.checkAvailable()
                val rejected = generateSequence<Throwable>(failure) { it.cause }.take(16).filterIsInstance<RejectedCertificate>().firstOrNull()
                if (rejected != null) {
                    guard.commit { synchronized(this) { checkOpen(); certificates.remember(rejected.problem) } }
                    return BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, attempt, certificate = rejected.problem)
                }
                if (failure is javax.net.ssl.SSLPeerUnverifiedException)
                    return BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, attempt)
                if (attempt == request.retry) return BrokerResult.Failure(RequestStage.Connect,
                    when (failure) {
                        is java.net.UnknownHostException -> FailureCode.Dns
                        is java.io.InterruptedIOException -> FailureCode.Timeout
                        else -> FailureCode.Network
                    }, attempt)
            }
        }
        error("Unreachable retry state")
    }

    private suspend fun redirects(request: BrokerRequest, first: HttpUrl, guard: RequestCommitGuard, policy: NetworkPolicy,
        paceSource: Boolean, route: SourceNetworkRoute): BrokerResponse {
        var url = first
        var method = request.method
        var body = request.body
        var callerHeaders = request.headers
        for (hop in 0..limits.maxRedirects) {
            policy.check(url)
            rate.withLock {
                val elapsed = (System.nanoTime() - lastStart) / 1_000_000
                if (lastStart != 0L && elapsed < limits.minIntervalMillis) delay(limits.minIntervalMillis - elapsed)
                // Each attempt is a logical source request. Redirect hops retain the broker's
                // hard interval but do not consume another source admission.
                if (paceSource && hop == 0) sourcePacing.awaitAdmission()
                currentCoroutineContext().ensureActive()
                checkOpen()
                lastStart = System.nanoTime()
            }
            val headers = headers(url, callerHeaders, policy)
            val bytes = body?.toByteArray(Charset.forName(request.charset))
            if (bytes != null && bytes.size > limits.maxRequestBytes) throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
            val requestBody = if (method in setOf("POST", "PUT", "PATCH") || bytes != null)
                (bytes ?: ByteArray(0)).toRequestBody(headers["Content-Type"]?.toMediaTypeOrNull()) else null
            val transport = clientFor(route, url)
            // Catalogue parsing can outlive a server's pooled connection. Let OkHttp recover
            // safe reads, without implicitly replaying login/submission bodies after a lost reply.
            val call = transport.newBuilder().retryOnConnectionFailure(method == "GET" || method == "HEAD")
                .dns(policy.dns(url, route.dns)).callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS).build()
                .newCall(Request.Builder().url(url).tag(NetworkPolicy::class.java, policy).headers(headers).method(method, requestBody).build())
            route.track(call)
            val response = try {
                awaitResponse(call, request.responseCharset, hop, guard, minOf(request.maxResponseBytes ?: limits.maxResponseBytes, limits.maxResponseBytes))
            } finally { route.finished(call, transport.connectionPool) }
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
            if (!request.followRedirects || response.status !in setOf(301, 302, 303, 307, 308) || location == null) return response
            if (hop == limits.maxRedirects) throw BrokerFailure(RequestStage.Response, FailureCode.RedirectLimit)
            val next = url.resolve(location) ?: throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
            policy.check(next)
            if (response.status == 303 && method != "HEAD" || response.status in setOf(301, 302) && method == "POST") {
                method = "GET"
                body = null
                callerHeaders = callerHeaders.filterKeys { !it.equals("Content-Type", true) }
            }
            if (NetworkPolicy.origin(next) != NetworkPolicy.origin(url)) {
                if (body != null) throw BrokerFailure(RequestStage.Permission, FailureCode.RedirectBodyDenied)
                // The website's client identity survives a redirect; credentials remain origin-bound.
                callerHeaders = callerHeaders.filterKeys { it.equals("User-Agent", true) }
            }
            url = next
        }
        error("Unreachable redirect state")
    }

    private fun headers(url: HttpUrl, explicit: Map<String, String>, policy: NetworkPolicy, includeCookies: Boolean = true): Headers {
        val headers = Headers.Builder()
        // Legado supplies a desktop UA even when the source has no header rule. Some sites
        // return HTTP 200 with null book/chapter data to OkHttp's default client identity.
        defaultUserAgent?.let { headers.set("User-Agent", it) }
        policy.check(url).headers.forEach { (key, value) -> headers.set(key, value) }
        val sameOrigin = sourceUrl.toHttpUrlOrNull()?.let { NetworkPolicy.origin(it) == NetworkPolicy.origin(url) } == true
        val loginHeaders = if (sameOrigin) (account.read(StorageRequestKey.LOGIN_HEADERS) as? StorageResult.Value)?.value else null
        loginHeaders?.let { Json.parseToJsonElement(it).let { json ->
            (json as kotlinx.serialization.json.JsonObject).forEach { (key, value) ->
                headers.set(key, (value as kotlinx.serialization.json.JsonPrimitive).content)
            }
        } }
        // A CDN learned from page data must not receive a source's arbitrary credential headers.
        val knownOrigin = grants.any { sourceOrigin(it.origin) == NetworkPolicy.origin(url) }
        explicit.forEach { (key, value) ->
            if (policy !== imagePolicy || knownOrigin || key.lowercase() in setOf("user-agent", "referer", "accept", "accept-language")) headers.set(key, value)
        }
        if (headers.build().names().any { it.lowercase() in setOf("host", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection") }) {
            throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
        }
        // Chromium owns its persistent cookie store; the mediated browser reads the jar
        // through its host bridge. Do not inject an HTTP jar snapshot as a native Cookie header.
        if (!includeCookies) return headers.build()
        // enabledCookieJar controls automatic HTTP response capture. Explicit login/verification
        // cookies are sent even when it is off (Legado AnalyzeUrl.setCookie).
        val cookie = if (policy !== imagePolicy || knownOrigin) cookies.header(url, headers["Cookie"])
            else headers["Cookie"].orEmpty()
        headers.removeAll("Cookie")
        if (cookie.isNotEmpty()) headers.set("Cookie", cookie)
        return headers.build()
    }

    /** The continuation remains cancellable through body consumption, so call.cancel interrupts reads too. */
    private suspend fun awaitResponse(call: Call, forcedCharset: String?, redirects: Int, guard: RequestCommitGuard, maxResponseBytes: Int): BrokerResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!continuation.isActive) return
                        if (response.headers.toString().length > 65536) throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                        val body = response.body
                        if (body.contentLength() > maxResponseBytes) throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                if (bytes.size() + read > maxResponseBytes) throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                                bytes.write(buffer, 0, read)
                            }
                        }
                        val responseBytes = bytes.toByteArray()
                        val declaredCharset = forcedCharset ?: body.contentType()?.charset()?.name()
                        val charset = declaredCharset ?: htmlResponseCharset(responseBytes, body.contentType()) ?: "UTF-8"
                        guard.commit {
                            synchronized(this@SourceSession) {
                                checkOpen()
                                if (continuation.isActive) {
                                    if (enabledCookieJar) cookies.save(response.request.url, response.headers)
                                    continuation.resume(BrokerResponse(response.code, response.request.url.toString(),
                                        response.headers.toMultimap().mapValues { it.value.toList() }, responseBytes, charset, redirects,
                                        message = response.message, protocol = response.protocol.toString(),
                                        sentAt = response.sentRequestAtMillis, receivedAt = response.receivedResponseAtMillis,
                                        declaredCharset = declaredCharset, method = response.request.method))
                                }
                            }
                        }
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            }
        }
        try { guard.commit { if (continuation.isActive) call.enqueue(callback) } }
        catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
    }

    private fun checkOpen() { if (closed) throw CancellationException("Source session retired") }
    @Synchronized override fun close() {
        lifetime.cancel()
        denied.clear()
        certificates.close()
        client.dispatcher.cancelAll()
        routeClients.forEach { (key, transport) -> key.first.detach(transport.connectionPool) }
        routeClients.clear()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Source-owned script values survive process restarts; zero TTL has no deadline. */
internal class ValueCache(private val limits: BrokerLimits, private val storage: SourceStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis) {
    @Serializable private data class Entry(val value: String, val deadline: Long)

    @Synchronized fun read(key: String): StorageResult = access { entries -> StorageResult.Value(entries[key]?.value) }

    @Synchronized fun write(request: StorageRequest): StorageResult = access { entries ->
        if (request.value == null) entries.remove(request.key)
        else {
            val ttl = request.ttlMillis ?: limits.cacheTtlMillis
            val size = entries.entries.filter { it.key != request.key }.sumOf { (it.key.length.toLong() + it.value.value.length) * 2 } +
                (request.key.length.toLong() + request.value.length) * 2
            if (size > limits.maxCacheBytes || request.key !in entries && entries.size >= limits.maxStorageEntries)
                return@access StorageResult.Failure(FailureCode.StorageQuota)
            entries[request.key] = Entry(request.value, if (ttl == 0L) 0 else Math.addExact(nowMillis(), ttl))
        }
        when (val saved = storage.write("entries", Json.encodeToString(entries))) {
            is StorageResult.Failure -> saved
            is StorageResult.Value -> StorageResult.Value(request.value)
        }
    }

    private inline fun access(block: (MutableMap<String, Entry>) -> StorageResult): StorageResult {
        val stored = storage.read("entries")
        if (stored !is StorageResult.Value) return stored
        return try {
            val entries = stored.value?.let { Json.decodeFromString<Map<String, Entry>>(it).toMutableMap() } ?: linkedMapOf()
            val now = nowMillis()
            entries.entries.removeAll { it.value.deadline != 0L && it.value.deadline <= now }
            block(entries)
        } catch (_: Exception) { StorageResult.Failure(FailureCode.StorageUnavailable) }
    }
}

private class ResponseCache(private val limits: BrokerLimits) {
    private val entries = linkedMapOf<String, Pair<Long, BrokerResponse>>()
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun get(key: String): BrokerResponse? {
        val cached = entries[key] ?: return null
        if ((System.nanoTime() - cached.first) / 1_000_000 >= limits.cacheTtlMillis) { entries.remove(key); return null }
        return copy(cached.second).copy(fromCache = true)
    }
    @Synchronized fun put(key: String, response: BrokerResponse) {
        if (size(response) > limits.maxCacheBytes) return
        entries[key] = System.nanoTime() to copy(response)
        while (entries.values.sumOf { size(it.second) } > limits.maxCacheBytes || entries.size > 256) entries.remove(entries.keys.first())
    }
    private fun size(response: BrokerResponse) = response.body.size.toLong() + response.finalUrl.length * 2L +
        response.headers.entries.sumOf { (it.key.length + it.value.sumOf(String::length)) * 2L }
    private fun copy(response: BrokerResponse) = response.copy(body = response.body.copyOf(), headers = response.headers.mapValues { it.value.toList() })
}
