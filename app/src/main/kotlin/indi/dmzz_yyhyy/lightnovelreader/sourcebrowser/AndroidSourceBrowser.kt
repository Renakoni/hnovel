package indi.dmzz_yyhyy.lightnovelreader.sourcebrowser

import android.content.*
import android.os.*
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One disposable Chromium process at a time, including API 24's process-wide browser directory. */
@Singleton
class AndroidSourceBrowser @Inject constructor(@ApplicationContext private val context: Context) : BrowserExecutor {
    private val serial = Mutex()

    override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
        guard: RequestCommitGuard): BrokerResult = serial.withLock { withContext(Dispatchers.IO) {
        require(options.title.length <= 1024 && options.script.length <= 65536 && options.sourceRegex.length <= 2048 &&
            options.delayMillis in 0..30000 && (options.html?.length ?: 0) <= 196608)
        require(!options.verificationCode || options.interactive)
        val connected = CompletableDeferred<IBrowserService>()
        val died = CompletableDeferred<Unit>()
        val result = CompletableDeferred<BrokerResult>()
        val alive = AtomicBoolean(true)
        val calls = AtomicInteger()
        val work = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        fun current(action: () -> Unit = {}) { check(alive.get() && !session.closed); guard.commit(action) }
        val host = object : IBrowserHost.Stub() {
            override fun call(operation: String, arguments: String): ParcelFileDescriptor {
                check(Binder.getCallingUid() == context.applicationInfo.uid)
                val answer = runCatching { runBlocking {
                    work.async {
                        current(); require(arguments.length <= 65536 && calls.incrementAndGet() <= 1024)
                        val args = Json.parseToJsonElement(arguments).jsonObject
                        val url = args.getValue("url").jsonPrimitive.content.toHttpUrl()
                        // Network operations must return the broker's typed refusal across Binder.
                        // Cookie/storage operations still need a preflight before accessing state.
                        if (operation !in setOf("initialRequest", "request")) check(session.permissionFailure(url.toString()) == null)
                        when (operation) {
                            "initialRequest" -> {
                                check(url == request.url.toHttpUrl())
                                // Use the host-owned request, including its explicit source Cookie.
                                val response = session.execute(request.copy(browser = null,
                                    maxResponseBytes = minOf(request.maxResponseBytes ?: 1024 * 1024, 1024 * 1024)), guard)
                                current(); Json.encodeToString(response)
                            }
                            "request" -> {
                                val headers = args["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                                    .filterKeys { it.lowercase() !in setOf("cookie", "host", "content-length", "connection", "accept-encoding") }
                                val response = session.execute(BrokerRequest("browser", url.toString(),
                                    method = args["method"]?.jsonPrimitive?.content ?: "GET", headers = headers,
                                    body = args["body"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                                    kind = args["kind"]?.jsonPrimitive?.content?.let(ResourceKind::valueOf) ?: ResourceKind.Document,
                                    timeoutMillis = request.timeoutMillis, maxResponseBytes = 1024 * 1024), guard)
                                current(); Json.encodeToString(response)
                            }
                            "cookie" -> {
                                var value = ""
                                current { value = session.browserCookie(url.toString(), args["value"]?.jsonPrimitive?.content) }
                                JsonPrimitive(value).toString()
                            }
                            "storage" -> {
                                val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                                val key = "browser/storage/$origin"
                                var stored: StorageResult? = null
                                current {
                                    stored = if ("value" in args) session.write(StorageRequest(StorageArea.Config, key,
                                        args.getValue("value").toString().also { require(it.length <= 32768) }))
                                    else session.read(StorageRequest(StorageArea.Config, key))
                                }
                                check(stored is StorageResult.Value)
                                (stored as StorageResult.Value).value ?: "{}"
                            }
                            else -> error("Browser operation denied")
                        }
                    }.await()
                } }.getOrElse { "null" }
                return BrowserWire.pipe(answer)
            }
            override fun complete(output: ParcelFileDescriptor) {
                check(Binder.getCallingUid() == context.applicationInfo.uid)
                work.launch {
                    try {
                        val completed = Json.decodeFromString<BrokerResult>(BrowserWire.read(output))
                        current(); result.complete(completed)
                    } catch (_: Exception) { output.close(); result.complete(BrokerResult.Failure(RequestStage.Response, FailureCode.Network)) }
                }
            }
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                binder.linkToDeath({ died.complete(Unit); result.complete(BrokerResult.Failure(RequestStage.Response, FailureCode.Network)) }, 0)
                connected.complete(IBrowserService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) { died.complete(Unit) }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Browser unavailable")) }
        }
        var remote: IBrowserService? = null
        var bound = false
        try {
            current()
            val flags = Context.BIND_AUTO_CREATE or
                if (options.interactive && Build.VERSION.SDK_INT >= 34) Context.BIND_ALLOW_ACTIVITY_STARTS else 0
            bound = context.bindService(Intent(context, SourceBrowserService::class.java), connection, flags)
            check(bound)
            remote = withTimeout(15000) { connected.await() }
            remote.start(Json.encodeToString(BrowserJob(request, options)), host)
            result.await().also { current() }
        } finally {
            alive.set(false); work.cancel()
            if (bound) context.unbindService(connection)
            runCatching { remote?.shutdown() }
            // Do not give the next owner a service whose old Chromium instance is still alive.
            withContext(NonCancellable) { withTimeout(5000) { if (remote != null) died.await() } }
            if (died.isCompleted) {
                val directory = context.getDir(if (Build.VERSION.SDK_INT >= 28) "webview_source_browser" else "webview", Context.MODE_PRIVATE)
                check(directory.canonicalFile.parentFile == File(context.applicationInfo.dataDir).canonicalFile)
                check(directory.deleteRecursively())
            }
        }
    } }
}
