package indi.renakoni.nextvol.sourcebrowser

import android.content.*
import android.os.*
import hnovel.network.*
import indi.renakoni.nextvol.data.web.AndroidSourceNetworks
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Host-owned admission and lifecycle. Website subrequests use Chromium's own network stack. */
internal class NativeSourceBrowser(private val context: Context, private val networks: AndroidSourceNetworks) {
    private class Connection(val context: Context, val profile: String, val route: SourceNetworkRoute?,
        val session: SourceSession?) : ServiceConnection {
        val ready = CompletableDeferred<IBrowserService>()
        val died = CompletableDeferred<Unit>()
        var pendingStorage: RetainedLocalStorage? = null
        var restoredStorage = false
        var cookieVersion = -1L
        @Volatile var failed = false
        val leases = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var remote: IBrowserService? = null
        private fun retire() { runCatching { remote?.shutdown() } }
        val invalidation = route?.onInvalidated(::retire)
        val retirement = session?.onClosed(::retire)
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                binder.linkToDeath({ died.complete(Unit) }, 0)
                val service = IBrowserService.Stub.asInterface(binder)
                remote = service
                ready.complete(service)
                if (route?.available == false || session?.closed == true) retire()
            } catch (failure: RemoteException) {
                died.complete(Unit); ready.completeExceptionally(failure)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { died.complete(Unit) }
        override fun onBindingDied(name: ComponentName) {
            died.complete(Unit); ready.completeExceptionally(IllegalStateException("Browser binding died"))
        }
        override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Browser unavailable")) }
    }

    companion object {
        // All instances share the one manifest process, including instrumentation hosts.
        internal const val PAGE_LIMIT = 4
        private val slots = NativeBrowserSlots(PAGE_LIMIT)
        private var connection: Connection? = null
        private suspend fun disconnect() {
            val old = connection ?: return
            connection = null
            try {
                if (!old.died.isCompleted) {
                    // Binding can still be in flight when the caller cancels. Wait for it
                    // before shutdown; never move a directory while Chromium may own it.
                    val remote = withTimeout(15000) { old.ready.await() }
                    runCatching { remote.shutdown() }
                    withTimeout(5000) { old.died.await() }
                }
            } finally { old.invalidation?.close(); old.retirement?.close(); old.context.unbindService(old) }
        }
    }

    private fun bind(owner: String, route: SourceNetworkRoute? = null, session: SourceSession? = null): Connection =
        Connection(context.applicationContext, owner, route, session).also {
            val flags = Context.BIND_AUTO_CREATE or if (Build.VERSION.SDK_INT >= 34) Context.BIND_ALLOW_ACTIVITY_STARTS else 0
            try { check(it.context.bindService(Intent(context, NativeSourceBrowserService::class.java), it, flags)) }
            catch (failure: Exception) { it.invalidation?.close(); it.retirement?.close(); throw failure }
            connection = it
        }

    private suspend fun fenceUnknownProcess(owner: String) {
        if (connection != null) return
        // A previous host may have left a bound service stopping. Its data files are
        // off limits until a new binding confirms that process has actually exited.
        val fence = bind(owner)
        withTimeout(15000) { fence.ready.await() }
        disconnect()
    }

    private suspend fun localStorage(bound: Connection, job: LocalStorageJob, current: () -> Unit = {}): Map<String, Map<String, String>> {
        val result = CompletableDeferred<LocalStorageResult>()
        val work = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        val host = object : IBrowserHost.Stub() {
            override fun call(operation: String, arguments: String): ParcelFileDescriptor = error("No page bridge")
            override fun complete(output: ParcelFileDescriptor) {
                check(Binder.getCallingUid() == context.applicationInfo.uid)
                work.launch {
                    try {
                        val value = Json.decodeFromString<LocalStorageResult>(BrowserWire.read(output, NativeBrowserRetention.MAX_BYTES))
                        current(); result.complete(value)
                    } catch (failure: Exception) { result.completeExceptionally(failure) }
                }.invokeOnCompletion { output.close() }
            }
        }
        try {
            val remote = withTimeout(15000) { bound.ready.await() }
            current()
            BrowserWire.pipe(Json.encodeToString(job)).use { remote.localStorage(it, host) }
            val response = withTimeout(20000) { select {
                result.onAwait { it }
                bound.died.onAwait { throw LocalStorageFailure() }
            } }
            current()
            response.failure?.let { throw LocalStorageFailure(it) }
            job.selection.validate(response.values)
            return response.values
        } finally { work.cancel() }
    }

    suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
        guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = withContext(Dispatchers.IO) {
        val files = NativeBrowserFiles(context)
        if (!files.supported)
            return@withContext BrokerResult.Failure(RequestStage.Parse, FailureCode.BrowserRequired)
        fun routeUnavailable() = BrokerResult.Failure(RequestStage.Connect, FailureCode.RouteUnavailable)
        if (!route.available) return@withContext routeUnavailable()
        val network = if (route.mode == SourceNetworkMode.BypassVpn)
            networks.boundNetwork(route) ?: return@withContext routeUnavailable() else null
        if (options.html != null || request.method != "GET" || !request.followRedirects || request.responseAsHex ||
            request.cache == CacheMode.Only ||
            request.headers.keys.any { it.equals("Cookie", true) })
            return@withContext BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest)
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        fun current() { check(alive.get() && !session.closed); guard.commit {} }
        current()
        val owner = nativeBrowserProfile(session.scope)
        val retention = NativeBrowserRetention(context.noBackupFilesDir, session.scope)
        val result = CompletableDeferred<BrokerResult>()
        val cookieVersion = java.util.concurrent.atomic.AtomicLong(-1)
        val work = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        val host = object : IBrowserHost.Stub() {
            override fun call(operation: String, arguments: String): ParcelFileDescriptor {
                check(Binder.getCallingUid() == context.applicationInfo.uid)
                require(operation == "cookies" && arguments.length <= 262144)
                val snapshots = Json.decodeFromString<List<NativeCookieSnapshot>>(arguments)
                require(snapshots.size <= 4)
                guard.commit {
                    check(alive.get() && work.isActive && !session.closed && route.available)
                    snapshots.filter { session.permissionFailure(it.url) == null }.forEach {
                        session.updateNativeBrowserCookies(it.url, it.cookies, it.completeMetadata, cookieVersion.get())
                    }
                }
                return BrowserWire.pipe("true")
            }
            override fun complete(output: ParcelFileDescriptor) {
                check(Binder.getCallingUid() == context.applicationInfo.uid)
                work.launch {
                    try {
                        val response = BrowserWire.readResult(output)
                        current(); result.complete(response)
                    } catch (failure: Exception) { result.completeExceptionally(failure) }
                }.invokeOnCompletion { output.close() }
            }
        }
        val jobId = java.util.UUID.randomUUID().toString()
        val parallel = options.sharedNativePage
        var lease: NativeBrowserSlots.Lease? = null
        var leaseConnection: Connection? = null
        var seed: NativeBrowserCookieSeed? = null
        lateinit var bound: Connection
        var remote: IBrowserService? = null
        var started = false
        var completed = false
        try {
            lease = slots.acquire(shared = {
                seed = session.nativeBrowserCookieSeed(request.url)
                parallel && connection?.let { it.session === session && it.route === route &&
                    !it.failed && !it.died.isCompleted && it.restoredStorage && it.cookieVersion == seed?.version } == true
            }) { exclusive ->
                if (!exclusive) bound = checkNotNull(connection)
                else try {
                    fenceUnknownProcess(owner)
                    if (connection?.session !== session || connection?.route !== route ||
                        connection?.failed == true || connection?.died?.isCompleted == true) disconnect()
                    current()
                    check(route.available)
                    bound = connection ?: run {
                        val stored = try { retention.read() } catch (_: Exception) { throw LocalStorageFailure() }
                        check(stored == null || stored.generation <= session.scope.accountGeneration) { "Native account retired" }
                        val pending = stored?.takeIf { it.generation == session.scope.accountGeneration && it.values != null }
                        if (session.scope.accountGeneration > 0 && (stored != null || session.localStorageRetention.origins.isNotEmpty()) &&
                            (stored == null || stored.generation < session.scope.accountGeneration)) guard.commit {
                            check(!session.closed)
                            // Fence late cleanup even when an earlier handoff failed or an account was skipped.
                            try { retention.write(RetainedLocalStorage(session.scope.accountGeneration)) }
                            catch (_: Exception) { throw LocalStorageFailure() }
                        }
                        // Recover a crash after the handoff was saved but before the retired profile was removed.
                        if (pending != null) files.clear(nativeBrowserProfile(session.scope.copy(accountGeneration = pending.generation - 1)))
                        files.prepare(owner)
                        bind(owner, route, session).also { it.pendingStorage = pending }
                    }
                    withTimeout(15000) { bound.ready.await() }
                    if (!bound.restoredStorage) {
                        val selected = session.localStorageRetention.select(bound.pendingStorage?.values.orEmpty())
                        if (selected.isNotEmpty()) localStorage(bound,
                            LocalStorageJob(owner, session.localStorageRetention, selected, network?.networkHandle), ::current)
                        bound.restoredStorage = true
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { disconnect() }
                    throw failure
                }
                // Host cookie changes start a new exclusive batch. A late website response
                // from the old batch cannot contaminate a newer page's cookie snapshot.
                if (exclusive) seed = session.nativeBrowserCookieSeed(request.url)
                bound.cookieVersion = checkNotNull(seed).version
                bound.leases.incrementAndGet()
                leaseConnection = bound
            }
            if (parallel) lease.single()
            val service = withTimeout(15000) { bound.ready.await() }.also { remote = it }
            session.awaitBrowserAdmission()
            current()
            if (!route.available) return@withContext routeUnavailable()
            val selectedSeed = checkNotNull(seed)
            cookieVersion.set(selectedSeed.version)
            started = true
            service.start(Json.encodeToString(BrowserJob(request, options, owner, session.enabledCookieJar,
                network?.networkHandle, session.certificateExceptions(), selectedSeed.cookies, jobId, selectedSeed.version)), host)
            val response = select {
                result.onAwait { it }
                bound.died.onAwait { BrokerResult.Failure(RequestStage.Response, FailureCode.Network) }
            }
            completed = true
            if (response is BrokerResult.Failure && response.stage == RequestStage.Connect) bound.failed = true
            current()
            if (!route.available) return@withContext routeUnavailable()
            if (response is BrokerResult.Success && bound.pendingStorage != null) {
                guard.commit {
                    check(!session.closed)
                    if (bound.pendingStorage != null) {
                        try { retention.write(RetainedLocalStorage(session.scope.accountGeneration)) }
                        catch (_: Exception) { throw LocalStorageFailure() }
                        bound.pendingStorage = null
                    }
                }
            }
            if (response is BrokerResult.Failure && response.code == FailureCode.BrowserRequired && !options.interactive) guard.commit {
                check(session.write(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL, request.url)) is StorageResult.Value)
            }
            val certificate = (response as? BrokerResult.Failure)?.certificate
            if (response is BrokerResult.Failure && response.code == FailureCode.Certificate && certificate != null) {
                var failure: BrokerResult.Failure? = null
                guard.commit { failure = session.browserCertificateFailure(certificate) }
                return@withContext checkNotNull(failure)
            }
            if (response is BrokerResult.Failure && response.challenge != null && !options.interactive)
                response.copy(verificationRequest = request.copy(browser = options))
            else response
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalStorageFailure) {
            if (!route.available) routeUnavailable() else BrokerResult.Failure(
                if (failure.code in setOf(FailureCode.RouteUnavailable, FailureCode.RouteUnsupported)) RequestStage.Connect else RequestStage.Storage,
                failure.code)
        }
        catch (failure: Exception) {
            if (!route.available) routeUnavailable() else throw failure
        } finally {
            alive.set(false)
            work.cancel()
            try {
                if (started && !completed) withContext(NonCancellable) {
                    // Retire cancelled pages' process-wide workers after the other current pages finish.
                    // New admissions wait for the retirement; an existing sibling keeps its browser.
                    bound.failed = true
                    // The service confirms this page is destroyed before its slot can be reused.
                    if (!runCatching { remote?.cancel(jobId) == true }.getOrDefault(false)) {
                        runCatching { remote?.shutdown() }
                        withTimeout(5000) { bound.died.await() }
                    }
                }
            } finally {
                try {
                    val owned = leaseConnection
                    if (owned != null && owned.leases.decrementAndGet() == 0 && owned.failed)
                        withContext(NonCancellable) { disconnect() }
                } finally { lease?.close() }
            }
        }
    }

    fun clearAccount(scope: SourceScope, selection: LocalStorageRetention) = runBlocking(Dispatchers.IO) { slots.exclusive {
        val owner = nativeBrowserProfile(scope)
        val files = NativeBrowserFiles(context)
        val retention = NativeBrowserRetention(context.noBackupFilesDir, scope)
        try {
            fenceUnknownProcess(owner)
            if (connection?.profile == owner) disconnect()
            try {
                val stored = retention.read()
                // A duplicate or late cleanup cannot replace a newer account's handoff.
                if ((stored == null || stored.generation <= scope.accountGeneration) &&
                    (selection.origins.isNotEmpty() || stored != null)) {
                    val values = if (stored?.generation == scope.accountGeneration && stored.values != null)
                        selection.select(stored.values)
                    else if (selection.origins.isNotEmpty() && files.exists(owner)) {
                        disconnect()
                        files.prepare(owner)
                        localStorage(bind(owner), LocalStorageJob(owner, selection))
                    } else emptyMap()
                    retention.write(RetainedLocalStorage(Math.addExact(scope.accountGeneration, 1),
                        values.takeIf { selection.origins.isNotEmpty() }))
                }
            } finally {
                // Snapshot failure never preserves the old credentials as a fallback.
                withContext(NonCancellable) {
                    if (connection?.profile == owner) disconnect()
                    files.clear(owner)
                }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { if (connection?.profile == owner) disconnect() }
            throw failure
        }
    } }
}
