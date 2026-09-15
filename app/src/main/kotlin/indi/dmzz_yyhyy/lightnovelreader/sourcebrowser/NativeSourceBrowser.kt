package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.content.*
import android.os.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.AndroidSourceNetworks
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Host-owned admission and lifecycle. Website subrequests use Chromium's own network stack. */
internal class NativeSourceBrowser(private val context: Context, private val networks: AndroidSourceNetworks) {
    private class Connection(val context: Context, val profile: String, val route: SourceNetworkRoute?,
        val session: SourceSession?) : ServiceConnection {
        val ready = CompletableDeferred<IBrowserService>()
        val died = CompletableDeferred<Unit>()
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
        private val serial = Mutex()
        private var connection: Connection? = null
        private fun profile(scope: SourceScope): String = MessageDigest.getInstance("SHA-256")
            .digest(Json.encodeToString(listOf(scope.namespace, scope.sourceId, scope.profile,
                scope.accountGeneration.toString())).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
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

    suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
        guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = withContext(Dispatchers.IO) { serial.withLock {
        val files = NativeBrowserFiles(context)
        if (!files.supported)
            return@withLock BrokerResult.Failure(RequestStage.Parse, FailureCode.BrowserRequired)
        fun routeUnavailable() = BrokerResult.Failure(RequestStage.Connect, FailureCode.RouteUnavailable)
        if (!route.available) return@withLock routeUnavailable()
        val network = if (route.mode == SourceNetworkMode.BypassVpn)
            networks.boundNetwork(route) ?: return@withLock routeUnavailable() else null
        if (options.html != null || request.method != "GET" || !request.followRedirects || request.responseAsHex ||
            request.cache == CacheMode.Only ||
            request.headers.keys.any { it.equals("Cookie", true) })
            return@withLock BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest)
        fun current() { check(!session.closed); guard.commit {} }
        current()
        val owner = profile(session.scope)
        val result = CompletableDeferred<BrokerResult>()
        val work = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        val host = object : IBrowserHost.Stub() {
            override fun call(operation: String, arguments: String): ParcelFileDescriptor = error("No page bridge")
            override fun complete(output: ParcelFileDescriptor) {
                check(Binder.getCallingUid() == context.applicationInfo.uid)
                work.launch {
                    try {
                        val response = Json.decodeFromString<BrokerResult>(BrowserWire.read(output))
                        current(); result.complete(response)
                    } catch (failure: Exception) { output.close(); result.completeExceptionally(failure) }
                }
            }
        }
        var completed = false
        try {
            fenceUnknownProcess(owner)
            if (connection?.session !== session || connection?.route !== route || connection?.died?.isCompleted == true) disconnect()
            current()
            if (!route.available) return@withLock routeUnavailable()
            val bound = connection ?: run { files.prepare(owner); bind(owner, route, session) }
            val remote = withTimeout(15000) { bound.ready.await() }
            session.awaitBrowserAdmission()
            current()
            if (!route.available) return@withLock routeUnavailable()
            remote.start(Json.encodeToString(BrowserJob(request, options, owner, session.enabledCookieJar, network?.networkHandle)), host)
            val response = select {
                result.onAwait { it }
                bound.died.onAwait { BrokerResult.Failure(RequestStage.Response, FailureCode.Network) }
            }
            current()
            if (!route.available) return@withLock routeUnavailable()
            if (response is BrokerResult.Failure && response.code == FailureCode.BrowserRequired && !options.interactive) guard.commit {
                check(session.write(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL, request.url)) is StorageResult.Value)
            }
            // A failed startup must not leave a half-initialized process for the next request.
            completed = response !is BrokerResult.Failure || response.stage != RequestStage.Connect
            if (response is BrokerResult.Failure && response.challenge != null && !options.interactive)
                response.copy(verificationRequest = request.copy(browser = options))
            else response
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!route.available) routeUnavailable() else throw failure
        } finally {
            work.cancel()
            // Normal task completion retains Chromium's in-memory session cookies. Cancellation
            // stops the owner process before another request can use or retire its directory.
            if (!completed) withContext(NonCancellable) { disconnect() }
        }
    } }

    fun clearAccount(scope: SourceScope) = runBlocking(Dispatchers.IO) { serial.withLock {
        val owner = profile(scope)
        try {
            fenceUnknownProcess(owner)
            if (connection?.profile == owner) disconnect()
            NativeBrowserFiles(context).clear(owner)
        } catch (failure: Exception) {
            withContext(NonCancellable) { disconnect() }
            throw failure
        }
    } }
}
