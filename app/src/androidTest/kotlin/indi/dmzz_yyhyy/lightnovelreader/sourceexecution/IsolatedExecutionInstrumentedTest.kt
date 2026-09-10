package indi.dmzz_yyhyy.lightnovelreader.sourceexecution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.ExecutionWire
import hnovel.execution.FailureCode
import hnovel.execution.SourceExecutionBroker
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import hnovel.network.NetworkGrant
import hnovel.network.BrokerLimits
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/UID tests. A Robolectric service is not a substitute for this suite. */
@RunWith(AndroidJUnit4::class)
class IsolatedExecutionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun savedBrokerMethodCannotOutliveItsInvocationInASharedLibrary() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-a", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "library-${java.util.UUID.randomUUID()}").toPath()
        val library = "var holder={};"
        try {
            MockWebServer().use { server ->
                server.start()
                SourceBroker(root).use { sessions ->
                    val session = sessions.open(SourceScope("fixture", "source-a", "legado"),
                        listOf(NetworkGrant(server.url("/").toString(), true)))
                    suspend fun run(code: String): ExecutionResult =
                        SourceExecutionBroker(id, authority, session, limits, server.url("/").toString()).use { broker ->
                            executor.execute(id, ExecutionTask.Script(code, libraryCode = library), limits, broker)
                        }
                    assertEquals(ExecutionResult.Success("1"), run("holder.ajax=java.ajax;1"))
                    assertEquals(ExecutionResult.Success("\"host bridge denied\""),
                        run("try{holder.ajax('/stale')}catch(e){e.message}"))
                    assertEquals(0, server.requestCount)
                    server.enqueue(MockResponse().setBody("current"))
                    assertEquals(ExecutionResult.Success("\"current\""), run("java.ajax('/current')"))
                    assertEquals("/current", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                }
            }
        } finally { executor.close() }
    }

    @Test fun sharedJsLibrarySurvivesBinderCallsAndResetsAfterRetirement() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1", "fixture", 1)
        val b = authority.issue("source-b", "legado", "1", "fixture", 1)
        val library = "var state={n:0};function next(){return ++state.n;}"
        val limits = ExecutionLimits(timeoutMillis = 15000)
        fun task(book: String) = ExecutionTask.Script("[next(),book.id,page,typeof invocationOnly]",
            bookId = book, page = 2, libraryCode = library)
        try {
            assertEquals(ExecutionResult.Success("1"), executor.execute(a,
                ExecutionTask.Script("var invocationOnly='private'; next()", libraryCode = library), limits))
            assertEquals(ExecutionResult.Success("[2,\"a2\",2,\"undefined\"]"), executor.execute(a, task("a2"), limits))
            assertEquals(ExecutionResult.Success("[1,\"b1\",2,\"undefined\"]"), executor.execute(b, task("b1"), limits))
            assertEquals(ExecutionResult.Success("[3,\"a3\",2,\"undefined\"]"), executor.execute(a, task("a3"), limits))
            val loggedIn = authority.issue("source-a", "legado", "1", "fixture", 2)
            assertEquals(ExecutionResult.Success("[1,\"new-account\",2,\"undefined\"]"), executor.execute(loggedIn, task("new-account"), limits))
            assertEquals(ExecutionResult.Failure(FailureCode.Timeout), executor.execute(a,
                ExecutionTask.Script("/(a+)+$/.test('a'.repeat(40)+'!')", libraryCode = library), ExecutionLimits(timeoutMillis = 4000)))
            assertEquals(ExecutionResult.Success("[1,\"restarted\",2,\"undefined\"]"), executor.execute(b, task("restarted"), limits))
            executor.close()
            assertEquals(ExecutionResult.Success("[1,\"closed\",2,\"undefined\"]"), executor.execute(b, task("closed"), limits))
        } finally { executor.close() }
    }

    @Test fun isolatedToolsMatchAndroidBase64AndPreserveNativeByteData() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-tools", "legado", "1")
        val expressions = mutableListOf<String>()
        val expected = mutableListOf<JsonElement>()
        for (text in listOf("", "a", "ab", "abc", "\uFFFF", "a".repeat(58), "\u4E2D".repeat(60))) {
            val literal = JsonPrimitive(text)
            for (flags in 0..31) {
                val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), flags)
                expressions += "java.base64Encode($literal,$flags)"
                expected += JsonPrimitive(encoded)
                expressions += "java.base64Decode(${JsonPrimitive(encoded)},$flags)"
                expected += JsonPrimitive(String(android.util.Base64.decode(encoded, flags), Charsets.UTF_8))
            }
        }
        for (encoded in listOf("YQ", " YQ==\n", "a", "YQ=", "Y!Q==", "YQ==YQ==", "77-_", "77+/", "")) {
            for (flags in listOf(0, 8)) {
                expressions += "(function(){try{return java.base64Decode(${JsonPrimitive(encoded)},$flags)}catch(e){return 'invalid'}})()"
                expected += JsonPrimitive(try { String(android.util.Base64.decode(encoded, flags), Charsets.UTF_8) }
                    catch (_: IllegalArgumentException) { "invalid" })
            }
        }
        expressions += "java.strToBytes('\u4E2D','GBK')"
        expected.add(JsonArray(listOf(JsonPrimitive(-42), JsonPrimitive(-48))))
        expressions += "java.bytesToStr(java.strToBytes('\u4E2D'))"
        expected += JsonPrimitive("\u4E2D")
        expressions += "java.md5Encode('abc')"
        expected += JsonPrimitive("900150983cd24fb0d6963f7d28e17f72")
        expressions += "java.HMacHex('data','HmacSHA256','key')"
        expected += JsonPrimitive("5031fe3d989c6d1537a013fa6e739da23463fdaec3b70137d828e36ace221bd0")
        val result = executor.execute(id, ExecutionTask.Script(expressions.joinToString(",", "[", "]")),
            ExecutionLimits(timeoutMillis = 15000))
        assertTrue(result.toString(), result is ExecutionResult.Success)
        assertEquals(JsonArray(expected), Json.parseToJsonElement((result as ExecutionResult.Success).output))
    }

    @Test fun rhinoCallsAuthenticatedHostBrokerAcrossIsolatedBinder() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-a", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "execution-${java.util.UUID.randomUUID()}").toPath()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(root).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "source-a", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                SourceExecutionBroker(id, authority, session, limits, server.url("/").toString()).use { broker ->
                    server.enqueue(MockResponse().setBody("isolated chapter"))
                    val result = executor.execute(id, ExecutionTask.Script(
                        "source.put('result',java.ajax('/chapter?page={{page}}')); [source.id,source.get('result')]"), limits, broker)
                    assertEquals(ExecutionResult.Success("[\"source-a\",\"isolated chapter\"]"), result)
                    assertEquals("/chapter?page=1", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                }
                val b = authority.issue("source-b", "legado", "1", "fixture")
                val other = sessions.open(SourceScope("fixture", "source-b", "legado"), emptyList())
                SourceExecutionBroker(b, authority, other, limits).use { broker ->
                    assertEquals(ExecutionResult.Success("\"\""), executor.execute(b,
                        ExecutionTask.Script("source.get('result')"), limits, broker))
                }
            }
        }
    }

    @Test fun runawayRhinoScriptDoesNotPreventTheNextSourceFromExecuting() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), executor.execute(a,
            ExecutionTask.Script("while(true){}"), limits))
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), executor.execute(a,
            ExecutionTask.Script("/(a+)+$/.test('a'.repeat(40)+'!')"), ExecutionLimits(timeoutMillis = 4000)))
        val b = authority.issue("source-b", "legado", "1")
        assertEquals(ExecutionResult.Success("42"), executor.execute(b, ExecutionTask.Script("21*2"), limits))
        assertEquals(ExecutionResult.Failure(FailureCode.ScriptRuntime), executor.execute(b,
            ExecutionTask.Script("Packages.java.lang.System.exit(0)"), limits))
    }

    @Test fun cancellingAjaxReleasesBrokerPermitForTheNextInvocation() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-a", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "cancel-${java.util.UUID.randomUUID()}").toPath()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(root, limits = BrokerLimits(concurrency = 1)).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "source-a", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val broker = SourceExecutionBroker(id, authority, session, limits, server.url("/").toString())
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async { executor.execute(id, ExecutionTask.Script("java.ajax('/slow')"), limits, broker) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                pending.cancel()
                withTimeout(5000) { pending.join() }
                server.enqueue(MockResponse().setBody("next"))
                SourceExecutionBroker(id, authority, session, limits, server.url("/").toString()).use { next ->
                    assertEquals(ExecutionResult.Success("\"next\""), executor.execute(id,
                        ExecutionTask.Script("java.ajax('/next')"), limits, next))
                }
                assertEquals("/next", server.takeRequest(3, TimeUnit.SECONDS)?.path)
            }
        }
    }

    @Test fun remoteBinderUsesIsolatedUidAndEnforcesWireLimits() = runBlocking {
        val connected = CompletableDeferred<IIsolatedExecutionService>()
        val death = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                binder.linkToDeath({ death.complete(Unit) }, 0)
                connected.complete(IIsolatedExecutionService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, IsolatedExecutionService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val service = withTimeout(15000) { connected.await() }
            assertNotEquals(Process.myUid(), service.workerUid())
            assertNull(service.asBinder().queryLocalInterface(IIsolatedExecutionService.Stub.DESCRIPTOR))
            assertForeignUidRejected(service.asBinder())
            val result = CompletableDeferred<ExecutionResult>()
            service.execute(ByteArray(IsolatedExecutionService.MAX_IPC_BYTES + 1), object : IExecutionCallback.Stub() {
                override fun onResult(bytes: ByteArray) { result.complete(ExecutionWire.decodeResult(bytes)) }
            }, null)
            assertEquals(ExecutionResult.Failure(FailureCode.InputLimit), withTimeout(5000) { result.await() })
            service.terminate()
            withTimeout(5000) { death.await() }
        } finally { context.unbindService(connection) }
    }

    private suspend fun assertForeignUidRejected(service: IBinder) {
        val connected = CompletableDeferred<IForeignExecutionProbe>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IForeignExecutionProbe.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, ForeignExecutionProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(withTimeout(15000) { connected.await() }.isRejected(service)) }
        finally { context.unbindService(connection) }
    }

    @Test fun timeoutKillsWorkerAndAnotherSourceCanStart() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), executor.execute(a,
            ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 4000)))
        val b = authority.issue("source-b", "legado", "1")
        assertEquals(ExecutionResult.Success("new process"), executor.execute(b,
            ExecutionTask.Echo("new process"), ExecutionLimits(timeoutMillis = 15000)))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidIdentity), executor.execute(
            b.copy(sourceId = "source-a"), ExecutionTask.Echo("forged")))
    }

    @Test fun revokeAndCancellationRetireWorkers() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val a = authority.issue("source-a", "legado", "1")
        val pending = async { executor.execute(a, ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 15000)) }
        delay(1500)
        authority.revoke(a)
        assertEquals(ExecutionResult.Failure(FailureCode.Revoked), withTimeout(5000) { pending.await() })
        val b = authority.issue("source-b", "legado", "1")
        val cancelled = async { executor.execute(b, ExecutionTask.Sleep(60000), ExecutionLimits(timeoutMillis = 15000)) }
        delay(1500)
        cancelled.cancel()
        withTimeout(5000) { cancelled.join() }
        assertEquals(ExecutionResult.Success("after cancellation"), executor.execute(b,
            ExecutionTask.Echo("after cancellation"), ExecutionLimits(timeoutMillis = 15000)))
    }
}
