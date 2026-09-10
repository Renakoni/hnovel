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
import hnovel.execution.ExecutedRule
import hnovel.rules.RuleValue
import hnovel.rules.OutputKind
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

    @Test fun coldStartupIsBoundedSeparatelyAndSuccessfulCallsReuseTheWorker() = runBlocking {
        val binds = java.util.concurrent.atomic.AtomicInteger()
        val connections = java.util.concurrent.ConcurrentHashMap<ServiceConnection, ServiceConnection>()
        val host = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                val first = binds.incrementAndGet() == 1
                val delayed = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (first) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            { connection.onServiceConnected(name, binder) }, 3000)
                        else connection.onServiceConnected(name, binder)
                    }
                    override fun onServiceDisconnected(name: ComponentName) = connection.onServiceDisconnected(name)
                    override fun onBindingDied(name: ComponentName) = connection.onBindingDied(name)
                    override fun onNullBinding(name: ComponentName) = connection.onNullBinding(name)
                }
                connections[connection] = delayed
                return super.bindService(intent, delayed, flags)
            }
            override fun unbindService(connection: ServiceConnection) = super.unbindService(connections.remove(connection)!!)
        }
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(host, authority)
        val first = authority.issue("first", "legado", "1")
        try {
            assertEquals(ExecutionResult.Success("ready"), executor.execute(first, ExecutionTask.Echo("ready"), ExecutionLimits(timeoutMillis = 2000)))
            val limits = ExecutionLimits(timeoutMillis = 15000)
            assertEquals(ExecutionResult.Success("1"), executor.execute(first, ExecutionTask.Script("var localOnly=1;localOnly"), limits))
            assertEquals(ExecutionResult.Success("\"undefined\""), executor.execute(first, ExecutionTask.Script("typeof localOnly"), limits))
            assertEquals(1, binds.get())
            authority.revoke(first)
            val second = authority.issue("second", "legado", "1")
            assertEquals(ExecutionResult.Success("next"), executor.execute(second, ExecutionTask.Echo("next"), limits))
            assertEquals(2, binds.get())
            assertEquals(ExecutionResult.Failure(FailureCode.ScriptRuntime), executor.execute(second, ExecutionTask.Script("throw new Error('fail')"), limits))
            assertEquals(ExecutionResult.Success("recovered"), executor.execute(second, ExecutionTask.Echo("recovered"), limits))
            assertEquals(3, binds.get())
        } finally { executor.close() }
    }

    @Test fun importedRulePipelineReachesReadingAcrossRealBinder() = runBlocking {
        hnovel.content.RuleSourceFixture().use { fixture ->
            val registry = indi.dmzz_yyhyy.lightnovelreader.data.web.WebSourceRegistry(fixture.authority)
            val accounts = indi.dmzz_yyhyy.lightnovelreader.data.web.SourceSessionManager(fixture.authority)
            val executor = AndroidIsolatedExecutor(context, fixture.authority)
            val runner = indi.dmzz_yyhyy.lightnovelreader.di.WebDataSourceModule.provideRuleTaskRunner(executor)
            val root = java.io.File(context.cacheDir, "pipeline-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val host = object : android.content.ContextWrapper(context) { override fun getFilesDir() = root }
            val service = indi.dmzz_yyhyy.lightnovelreader.data.web.rules.ImportedRuleSources(host, registry, fixture.authority, accounts, runner)
            try {
                val preview = service.importer.preview(fixture.raw().toString())
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                assertNull(service.importer.commit(preview, listOf(hnovel.imports.ImportSelection(0, hnovel.imports.ImportDecision.Add))).error)
                val definition = service.definitions.list().single()
                val id = service.activate(definition.reference(), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val runtime = (registry.resolve(id) as indi.dmzz_yyhyy.lightnovelreader.data.web.SourceResolution.Ready).runtime
                val remoteId = fixture.server.url("/book/one").toString()
                val info = runtime.getBookInformation(remoteId)
                assertTrue(info.toString(), info.isOk)
                val directory = runtime.getBookVolumes(remoteId).component1()!!
                assertEquals(2, directory.volumes.single().chapters.size)
                val first = directory.volumes.single().chapters.first()
                val content = runtime.getChapterContent(first.id, remoteId).component1()!!
                assertEquals(first.id, content.id)
                assertTrue(content.content.toString(), content.content.toString().contains("A first"))
                assertTrue(content.content.toString().contains("last replaced"))
                assertArrayEquals(byteArrayOf(3, 2, 1), runtime.imageBytes(remoteId, fixture.server.url("/cover.png").toString(), true).component1())
            } finally { service.stop(); executor.close(); root.deleteRecursively() }
        }
    }

    @Test fun ordinaryResponseSizesAndRetainedHeadersRespectBudgetsAcrossBinder() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("response-budgets", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 30000)
        val root = java.io.File(context.cacheDir, "response-budgets-${java.util.UUID.randomUUID()}")
        val library = "var holder={};"
        try {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString()
                SourceBroker(root.toPath()).use { sessions ->
                    val session = sessions.open(SourceScope("fixture", "response-budgets", "legado"), listOf(NetworkGrant(base, true)))
                    suspend fun execute(code: String) = SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                        executor.execute(id, ExecutionTask.Script(code, libraryCode = library, baseUrl = base), limits, broker)
                    }
                    server.enqueue(MockResponse().setBody("x".repeat(60000)).addHeader("X-Test", "first"))
                    assertEquals(ExecutionResult.Success("60000"), execute("""
                        holder.r=java.get(baseUrl,{});holder.r.addHeader('X-Retained','first');
                        holder.headers=holder.r.multiHeaders().get('X-Retained');holder.r.body().length
                    """))
                    assertEquals(ExecutionResult.Success("1"), execute("holder.headers.add('second');1"))
                    assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), execute("holder.headers.add(new Array(10001).join('x'));1"))
                    assertEquals(ExecutionResult.Success("\"undefined\""), execute("typeof holder.r"))
                    server.enqueue(MockResponse().setBody("\u4E2D".repeat(20000)))
                    assertEquals(ExecutionResult.Success("[20000,60000]"), execute("var r=java.connect(baseUrl);[r.body().length,r.raw().body().contentLength()]"))
                    repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(30000))) }
                    assertEquals(ExecutionResult.Success("[30000,30000]"), execute("java.ajaxAll([baseUrl,baseUrl]).map(function(r){return r.body().length})"))
                }
            }
        } finally { executor.close(); root.deleteRecursively() }
    }

    @Test fun responseBytesDomAndEntityWritesCrossRealBinderBoundary() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("response-data", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 30000)
        val root = java.io.File(context.cacheDir, "response-data-${java.util.UUID.randomUUID()}")
        try {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString()
                server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0,127,-128,-1))))
                val html = "<meta charset=windows-1252><p>caf\u00e9</p>".toByteArray(charset("windows-1252"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(okio.Buffer().write(html)))
                SourceBroker(root.toPath()).use { sessions ->
                    val session = sessions.open(SourceScope("fixture", "response-data", "legado"), listOf(NetworkGrant(base, true)))
                    SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                        val result = executor.execute(id, ExecutionTask.Script("""
                            var stream=java.get(baseUrl+'bytes',{}).bodyStream(),bytes=[0,0,0,0];stream.read(bytes);stream.close();
                            var response=java.get(baseUrl+'html',{}),before=response.charset(),doc=response.parse();
                            doc.selectFirst('p').appendText('!').dataset().put('chapter','one');
                            [bytes,before,doc.selectFirst('p').text(),response.charset(),doc.selectFirst('p').attr('data-chapter'),typeof doc.getClass]
                        """, baseUrl=base), limits, broker)
                        assertEquals(ExecutionResult.Success("[[0,127,-128,-1],null,\"café!\",\"windows-1252\",\"one\",\"undefined\"]"), result)
                    }
                }
            }
            val metadataId = authority.issue("metadata-data", "legado", "1")
            val result = executor.execute(metadataId, ExecutionTask.Rule("""<js>
                book.setName('Updated');book.putVariable('big',new Array(10001).join('x'));result
                </js><js>book.name+':'+book.getVariable('big').length</js>""",RuleValue.Text("input"),OutputKind.Text),limits)
            assertTrue(result.toString(),result is ExecutionResult.Success)
            val data=Json.decodeFromString(ExecutedRule.serializer(),(result as ExecutionResult.Success).output)
            assertEquals(RuleValue.Text("Updated:10000"),data.value)
            assertEquals("Updated",data.book!!.getValue("name").jsonPrimitive.content)
            assertEquals(10000,data.bookBigWrites.getValue("big")!!.length)
        } finally { root.deleteRecursively() }
    }

    @Test fun oversizedArchivesMatchPortableSizeFailuresAcrossTheIsolatedBoundary() = runBlocking {
        fun zip(vararg entries: Pair<String, Int>) = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { archive ->
                for ((name, size) in entries) {
                    archive.putNextEntry(java.util.zip.ZipEntry(name)); archive.write(ByteArray(size)); archive.closeEntry()
                }
            }
        }.toByteArray()
        val exact = zip("chapter" to 65536)
        for (decoder in listOf(hnovel.rhino.ArchiveDecoder.Zip, AndroidArchiveDecoder)) {
            assertEquals(65536, decoder.decode(exact, 65536).getValue("chapter").size)
            assertTrue(runCatching { decoder.decode(exact, exact.size - 1) }.exceptionOrNull() is hnovel.rhino.ArchiveSizeLimitExceeded)
        }
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("archive-limits", "legado", "1")
        val limits = ExecutionLimits(timeoutMillis = 30000)
        try {
            for (bytes in listOf(zip("chapter" to 65537), zip("chapter" to 40000, "other" to 40000))) {
                val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), executor.execute(id,
                    ExecutionTask.Script("java.getZipStringContent('$hex','chapter')"), limits))
            }
            val invalid = zip("../chapter" to 1).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), executor.execute(id,
                ExecutionTask.Script("java.getZipStringContent('$invalid','chapter')"), limits))
            assertEquals(ExecutionResult.Success("42"), executor.execute(id, ExecutionTask.Script("21*2"), limits))
        } finally { executor.close() }
    }

    @Test fun nativeArchivesFontsConversionAndMetadataRunInIsolatedProcess() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("native-tools", "legado", "1")
        val limits = ExecutionLimits(timeoutMillis = 30000)
        fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name").use { it.readBytes() }
        for ((extension, method) in listOf("zip" to "Zip", "rar" to "Rar", "7z" to "7z")) {
            val hex = fixture("chapter.$extension").joinToString("") { "%02x".format(it.toInt() and 255) }
            val result = executor.execute(id, ExecutionTask.Script("java.get${method}StringContent('$hex','chapter.txt')"), limits)
            assertEquals(extension, ExecutionResult.Success("\"synthetic chapter\""), result)
        }
        val good = java.util.Base64.getEncoder().encodeToString(fixture("plain.ttf"))
        val bad = java.util.Base64.getEncoder().encodeToString(fixture("obfuscated.ttf"))
        val supplementary = java.util.Base64.getEncoder().encodeToString(fixture("supplementary.ttf"))
        assertEquals(ExecutionResult.Success("\"A\""), executor.execute(id, ExecutionTask.Script(
            "java.replaceFont(String.fromCodePoint(0x100000),java.queryTTF('$supplementary'),java.queryTTF('$good'))"),limits))
        val task = ExecutionTask.Script("""
            var good=java.queryTTF('$good');var bad=java.queryTTF('$bad');
            [java.replaceFont('\uE000',bad,good),java.t2s('龍與書'),java.s2t('龙与书'),book.name,chapter.title]
        """, book=buildJsonObject { put("name", "Book") }, chapter=buildJsonObject { put("title", "Chapter") })
        assertEquals(ExecutionResult.Success("[\"A\",\"龙与书\",\"龍與書\",\"Book\",\"Chapter\"]"), executor.execute(id, task, limits))
    }

    @Test fun cryptoNestedRulesTemplatesAndSourceResourcesExecuteInIsolatedWorker() = runBlocking {
        val authority=ExecutionAuthority()
        val executor=AndroidIsolatedExecutor(context,authority)
        val id=authority.issue("tools","legado","1","fixture")
        val limits=ExecutionLimits(timeoutMillis=20000,maxRequests=20)
        val root=java.io.File(context.cacheDir,"tools-${java.util.UUID.randomUUID()}").toPath()
        try {
            MockWebServer().use { server ->
                server.start()
                val base=server.url("/").toString()
                SourceBroker(root).use { sessions ->
                    val session=sessions.open(SourceScope("fixture","tools","legado"),listOf(NetworkGrant(base,true)))
                    server.enqueue(MockResponse().setBody("network"))
                    server.enqueue(MockResponse().setBody("40+2"))
                    val code="""
                        var c=java.createSymmetricCrypto('AES/ECB/PKCS5Padding','0123456789abcdef');
                        var s=java.createSign('SHA256withRSA');
                        var r=java.createAsymmetricCrypto('RSA');
                        var net=java.ajax('/find/{{page+1}},'+JSON.stringify({js:'result+"?ok=1"'}));
                        var path=java.downloadFile('/script.js');
                        java.put('value',java.getString('@js:c.decryptStr(c.encrypt("chapter"))'));
                        [java.getString('a@text'),java.get('value'),s.verify(java.strToBytes('chapter'),s.sign('chapter')),
                         r.decryptStr(r.encrypt('rsa'),false),net,java.importScript(path),java.readFile(path).length]
                    """.trimIndent()
                    SourceExecutionBroker(id,authority,session,limits,base,page=2).use { broker ->
                        val result=executor.execute(id,ExecutionTask.Rule("@js:$code",RuleValue.Text("<a>One</a>"),
                            baseUrl=base,page=2),limits,broker)
                        assertTrue(result.toString(),result is ExecutionResult.Success)
                        val rule=Json.decodeFromString(ExecutedRule.serializer(),(result as ExecutionResult.Success).output)
                        assertEquals(RuleValue.Items(listOf("One","chapter","true","rsa","network","40+2","4").map(RuleValue::Text)),rule.value)
                        assertEquals(mapOf("value" to "chapter"),rule.writes)
                    }
                    assertEquals("/find/3?ok=1",server.takeRequest(3,TimeUnit.SECONDS)?.path)
                    assertEquals("/script.js",server.takeRequest(3,TimeUnit.SECONDS)?.path)
                    val html="<p>First</p><p>Second</p>"
                    val expected=html.replace(Regex("</?p>"),"\n").replace(Regex("\\s*\\n+\\s*"),"\n\u3000\u3000")
                        .replace(Regex("^[\\n\\s]+"),"\u3000\u3000").replace(Regex("[\\n\\s]+$"),"")
                    val formatted=executor.execute(id,ExecutionTask.Script("java.htmlFormat(${JsonPrimitive(html)})"),limits) as ExecutionResult.Success
                    assertEquals(expected,Json.parseToJsonElement(formatted.output).jsonPrimitive.content)
                }
            }
        } finally { executor.close() }
    }

    @Test fun networkResponseViewsCrossBinderAndKeepRuleGetOverloadsDistinct() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("responses", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "responses-${java.util.UUID.randomUUID()}").toPath()
        try {
            MockWebServer().use { server ->
                server.start()
                val base = server.url("/").toString()
                SourceBroker(root).use { sessions ->
                    val session = sessions.open(SourceScope("fixture", "responses", "legado"), listOf(NetworkGrant(base, true)))
                    server.enqueue(MockResponse().setBody("connected").addHeader("X-Test", "value"))
                    server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://denied.invalid/"))
                    val code = "var a=java.connect('/one');var b=java.get('$base',{});" +
                        "[java.get('variable'),a.body(),a.headers().get('x-test'),b.statusCode(),b.header('Location')]"
                    SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                        val result = executor.execute(id, ExecutionTask.Rule("@js:$code", RuleValue.Text(""),
                            sourceVariables = mapOf("variable" to "local"), baseUrl = base), limits, broker) as ExecutionResult.Success
                        assertEquals(RuleValue.Items(listOf("local", "connected", "value", "302", "https://denied.invalid/").map(RuleValue::Text)),
                            Json.decodeFromString(ExecutedRule.serializer(), result.output).value)
                    }
                    assertEquals(2, server.requestCount)
                    server.enqueue(MockResponse().setBody("first"))
                    server.enqueue(MockResponse().setBody("second"))
                    SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                        val result = executor.execute(id, ExecutionTask.Script("java.ajaxAll(['/a','/b']).map(function(r){return r.code()})", baseUrl = base), limits, broker)
                        assertEquals(ExecutionResult.Success("[200,200]"), result)
                    }
                    SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                        assertEquals(ExecutionResult.Success("[16,true,true]"), executor.execute(id,
                            ExecutionTask.Script("var value=java.androidId();[value.length,/^[0-9a-f]{16}$/.test(value),value===java.androidId()]", baseUrl = base), limits, broker))
                    }
                }
            }
        } finally { executor.close() }
    }

    @Test fun closedAndReplacedSessionsRevokeComputationAndRetainedLibraries() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "lifetime-${java.util.UUID.randomUUID()}").toPath()
        val scope = SourceScope("fixture", "lifetime", "legado")
        val task = ExecutionTask.Script("++state.n", libraryCode = "var state={n:0};")
        try {
            SourceBroker(root).use { sessions ->
                var session = sessions.open(scope, emptyList())
                val first = authority.issue("lifetime", "legado", "1", "fixture")
                suspend fun run(id: hnovel.execution.ExecutionIdentity) = SourceExecutionBroker(id, authority, session, limits).use {
                    executor.execute(id, task, limits, it)
                }
                assertEquals(ExecutionResult.Success("1"), run(first))
                assertEquals(ExecutionResult.Success("2"), run(first))
                session.close()
                assertFalse(authority.accepts(first))
                session = sessions.open(scope, emptyList())
                val second = authority.issue("lifetime", "legado", "1", "fixture")
                assertEquals(ExecutionResult.Success("1"), run(second))
                SourceExecutionBroker(second, authority, session, limits).use { broker ->
                    val pending = async { executor.execute(second, ExecutionTask.Sleep(10000), limits, broker) }
                    delay(300)
                    session = sessions.open(scope.copy(accountGeneration = 1), emptyList())
                    assertEquals(ExecutionResult.Failure(FailureCode.Revoked), withTimeout(5000) { pending.await() })
                }
                val third = authority.issue("lifetime", "legado", "1", "fixture", 1)
                assertEquals(ExecutionResult.Success("1"), run(third))
            }
        } finally { executor.close() }
    }

    @Test fun brokerAndTaskMustSharePageKeywordAndBaseUrlBeforeAnyRequest() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("context", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "context-${java.util.UUID.randomUUID()}").toPath()
        try {
            MockWebServer().use { server ->
                server.start()
                SourceBroker(root).use { sessions ->
                    val base = server.url("/root/").toString()
                    val session = sessions.open(SourceScope("fixture", "context", "legado"),
                        listOf(NetworkGrant(server.url("/").toString(), true)))
                    val code = "java.ajax('chapter?page={{page}}&q={{key}}')"
                    val script = ExecutionTask.Script(code, key = "chapter space", page = 2, baseUrl = base)
                    val rule = ExecutionTask.Rule("@js:$code", RuleValue.Text(""), OutputKind.Text,
                        key = script.key, page = script.page, baseUrl = base)
                    val mismatches = listOf(script.copy(page = 1), script.copy(key = "wrong"), script.copy(baseUrl = ""),
                        rule.copy(page = 1), rule.copy(key = "wrong"), rule.copy(baseUrl = ""))
                    for (task in mismatches) {
                        SourceExecutionBroker(id, authority, session, limits, base, script.key, script.page).use { broker ->
                            assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), executor.execute(id, task, limits, broker))
                        }
                    }
                    assertEquals(0, server.requestCount)
                    for (task in listOf(script, rule)) {
                        server.enqueue(MockResponse().setBody("served"))
                        SourceExecutionBroker(id, authority, session, limits, base, script.key, script.page).use { broker ->
                            val response = executor.execute(id, task, limits, broker) as ExecutionResult.Success
                            if (task is ExecutionTask.Script) assertEquals("\"served\"", response.output)
                            else assertEquals(RuleValue.Text("served"), Json.decodeFromString(ExecutedRule.serializer(), response.output).value)
                        }
                        assertEquals("/root/chapter?page=2&q=chapter+space", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    }
                }
            }
        } finally { executor.close() }
    }

    @Test fun allocationFailureKillsWorkerAndAnotherSourceCanRun() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            val id = authority.issue("allocator", "legado", "1")
            // Keep the allocation reachable. A transient buffer may be collected before the
            // next sample; the documented monitor is an allocated-memory budget, not RSS.
            val result = executor.execute(id, ExecutionTask.Script("holder.bytes=new ArrayBuffer(192*1024*1024);holder.bytes.byteLength",
                libraryCode = "var holder={};"),
                ExecutionLimits(timeoutMillis = 15000))
            assertEquals(ExecutionResult.Failure(FailureCode.ProcessExited), result)
            val next = authority.issue("other", "legado", "1")
            assertEquals(ExecutionResult.Success("42"), executor.execute(next, ExecutionTask.Script("21*2"),
                ExecutionLimits(timeoutMillis = 15000)))
        } finally { executor.close() }
    }

    @Test fun legacyCipherHelpersMatchAndroidProvidersInsideTheIsolatedWorker() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("crypto", "legado", "1")
        val expressions = mutableListOf<String>()
        val expected = mutableListOf<JsonElement>()
        for ((algorithm, key) in listOf("AES" to "0123456789abcdef", "DES" to "01234567", "DESede" to "0123456789abcdefABCDEFGH")) {
            val cipher = javax.crypto.Cipher.getInstance("$algorithm/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.toByteArray(), algorithm))
            val encoded = android.util.Base64.encodeToString(cipher.doFinal("chapter".toByteArray()), android.util.Base64.NO_WRAP)
            val encrypt = when (algorithm) {
                "AES" -> "java.aesEncodeToBase64String('chapter','$key','AES/ECB/PKCS5Padding','')"
                "DES" -> "java.desEncodeToBase64String('chapter','$key','DES/ECB/PKCS5Padding','')"
                else -> "java.tripleDESEncodeBase64Str('chapter','$key','ECB','PKCS5Padding','')"
            }
            val decrypt = when (algorithm) {
                "AES" -> "java.aesEncodeToString('$encoded','$key','AES/ECB/PKCS5Padding','')"
                "DES" -> "java.desDecodeToString('$encoded','$key','DES/ECB/PKCS5Padding','')"
                else -> "java.tripleDESDecodeStr('$encoded','$key','ECB','PKCS5Padding','')"
            }
            expressions += encrypt
            expected += JsonPrimitive(encoded)
            expressions += decrypt
            expected += JsonPrimitive("chapter")
        }
        try {
            val result = executor.execute(id, ExecutionTask.Script(expressions.joinToString(",", "[", "]")), ExecutionLimits(timeoutMillis = 15000))
            assertTrue(result.toString(), result is ExecutionResult.Success)
            assertEquals(JsonArray(expected), Json.parseToJsonElement((result as ExecutionResult.Success).output))
        } finally { executor.close() }
    }

    @Test fun externalLibrariesFeedIsolatedRulesAndCacheCannotBypassPermissionChanges() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        var id = authority.issue("source-a", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "external-${java.util.UUID.randomUUID()}").toPath()
        try {
            MockWebServer().use { server ->
                server.start()
                val definition = buildJsonObject { put("first", server.url("/one").toString()); put("second", server.url("/two").toString()) }.toString()
                val task = ExecutionTask.Rule("tag.li@text@js:result.map(label)", RuleValue.Text("<li>A</li><li>B</li>"), libraryCode = definition)
                SourceBroker(root).use { sessions ->
                    val scope = SourceScope("fixture", "source-a", "legado")
                    val denied = sessions.open(scope, emptyList())
                    SourceExecutionBroker(id, authority, denied, limits).use { broker ->
                        assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), executor.execute(id, task, limits, broker))
                    }
                    assertEquals(0, server.requestCount)
                    denied.close()
                    id = authority.issue("source-a", "legado", "1", "fixture")
                    val session = sessions.open(scope, listOf(NetworkGrant(server.url("/").toString(), true)))
                    server.enqueue(MockResponse().setBody("'use strict'; var state={n:0};"))
                    server.enqueue(MockResponse().setBody("function label(x){return x+(++state.n)+(this===undefined?'strict':'loose');}"))
                    suspend fun runRule(): ExecutedRule = SourceExecutionBroker(id, authority, session, limits).use { broker ->
                        val result = executor.execute(id, task, limits, broker)
                        assertTrue(result.toString(), result is ExecutionResult.Success)
                        Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
                    }
                    assertEquals(RuleValue.Items(listOf(RuleValue.Text("A1loose"), RuleValue.Text("B2loose"))), runRule().value)
                    assertEquals(RuleValue.Items(listOf(RuleValue.Text("A3loose"), RuleValue.Text("B4loose"))), runRule().value)
                    assertEquals(2, server.requestCount)
                    session.close()
                    id = authority.issue("source-a", "legado", "1", "fixture")
                    val revoked = sessions.open(scope, emptyList())
                    SourceExecutionBroker(id, authority, revoked, limits).use { broker ->
                        assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied), executor.execute(id, task, limits, broker))
                    }
                    assertEquals(2, server.requestCount)
                }
            }
        } finally { executor.close() }
    }

    @Test fun libraryDownloadDeadlineAndRevocationReleaseTheSingleBrokerPermit() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val root = java.io.File(context.cacheDir, "library-cancel-${java.util.UUID.randomUUID()}").toPath()
        try {
            MockWebServer().use { server ->
                server.start()
                SourceBroker(root, limits = BrokerLimits(concurrency = 1)).use { sessions ->
                    val session = sessions.open(SourceScope("fixture", "source-a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                    val definition = buildJsonObject { put("lib", server.url("/slow").toString()) }.toString()
                    val task = ExecutionTask.Script("1", libraryCode = definition)
                    for (revoke in listOf(false, true)) {
                        val id = authority.issue("source-a", "legado", "1", "fixture")
                        val limits = ExecutionLimits(timeoutMillis = if (revoke) 15000 else 2000)
                        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                        SourceExecutionBroker(id, authority, session, limits).use { broker ->
                            val pending = async { executor.execute(id, task, limits, broker) }
                            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                            if (revoke) authority.revoke(id)
                            assertEquals(ExecutionResult.Failure(if (revoke) FailureCode.Revoked else FailureCode.Timeout),
                                withTimeout(5000) { pending.await() })
                        }
                    }
                    val next = authority.issue("source-a", "legado", "1", "fixture")
                    val limits = ExecutionLimits(timeoutMillis = 15000)
                    server.enqueue(MockResponse().setBody("var ready=true;"))
                    SourceExecutionBroker(next, authority, session, limits).use { broker ->
                        assertEquals(ExecutionResult.Success("true"), executor.execute(next, task.copy(code = "ready"), limits, broker))
                    }
                }
            }
        } finally { executor.close() }
    }

    @Test fun ruleScriptRegexIsKilledAndTheNextRuleCanSelectNormally() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-a", "legado", "1")
        try {
            val failure = executor.execute(id,
                ExecutionTask.Rule("@js:/(a+)+$/.test(result)", RuleValue.Text("a".repeat(40) + "!")), ExecutionLimits(timeoutMillis = 4000)) as ExecutionResult.Failure
            assertEquals(FailureCode.Timeout, failure.code)
            // Instruction exhaustion has a stage; host process-deadline termination has none.
            failure.ruleError?.let { assertEquals(hnovel.rules.RuleStage.Script, it.stage) }
            val other = authority.issue("source-b", "legado", "1")
            val result = executor.execute(other, ExecutionTask.Rule("tag.h1@text", RuleValue.Text("<h1>next</h1>"), OutputKind.Text),
                ExecutionLimits(timeoutMillis = 15000)) as ExecutionResult.Success
            assertEquals(RuleValue.Text("next"), Json.decodeFromString(ExecutedRule.serializer(), result.output).value)
        } finally { executor.close() }
    }

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
                            executor.execute(id, ExecutionTask.Script(code, libraryCode = library, baseUrl = server.url("/").toString()), limits, broker)
                        }
                    assertEquals(ExecutionResult.Success("1"), run("holder.ajax=java.ajax.bind(java);1"))
                    assertEquals(ExecutionResult.Success("\"host bridge denied\""),
                        run("try{holder.ajax('/stale')}catch(e){e.message}"))
                    assertEquals(0, server.requestCount)
                    server.enqueue(MockResponse().setBody("current"))
                    assertEquals(ExecutionResult.Success("\"current\""), run("java.ajax.call(null,'/current')"))
                    assertEquals("/current", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                }
            }
        } finally { executor.close() }
    }

    @Test fun oversizedDomMutationDiscardsRetainedLibraryStateAcrossBinderCalls() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("dom-reset", "legado", "1")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val library = "var saved={};var counter={value:0};"
        fun task(code: String) = ExecutionTask.Script(code, libraryCode = library,
            result = JsonPrimitive("<p>A</p>"))
        try {
            repeat(2) {
                assertEquals(ExecutionResult.Success("1"), executor.execute(id, task("""
                    saved.node=java.getElements('p').first();saved.alias=saved.node;
                    saved.node.append(new Array(60001).join('x'));++counter.value
                """), limits))
                assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), executor.execute(id, task("""
                    try{saved.node.append(new Array(10001).join('x'))}catch(e){}
                """), limits))
                assertEquals(ExecutionResult.Success("[\"undefined\",\"undefined\",0]"), executor.execute(id,
                    task("[typeof saved.node,typeof saved.alias,counter.value]"), limits))
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

    @Test fun dynamicSourceHeadersRunOnlyForNetworkCallsAcrossIsolatedBinder() = runBlocking {
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val id = authority.issue("source-header", "legado", "1", "fixture")
        val limits = ExecutionLimits(timeoutMillis = 15000)
        val root = java.io.File(context.cacheDir, "header-${java.util.UUID.randomUUID()}").toPath()
        try {
            MockWebServer().use { server ->
                server.start()
                SourceBroker(root).use { sessions ->
                    val base = server.url("/").toString()
                    val session = sessions.open(SourceScope("fixture", "source-header", "legado"), listOf(NetworkGrant(base, true)))
                    suspend fun run(rule: String): RuleValue =
                        SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                            val result = executor.execute(id, ExecutionTask.Rule(rule, RuleValue.Text("<h1>local</h1>"),
                                output = OutputKind.Text, baseUrl = base,
                                sourceHeaderRule = "@js:JSON.stringify({Authorization:java.ajax('/token')})"), limits, broker)
                            assertTrue(result.toString(), result is ExecutionResult.Success)
                            Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value
                        }
                    assertEquals(RuleValue.Text("local"), run("h1@text"))
                    assertEquals(0, server.requestCount)
                    server.enqueue(MockResponse().setBody("source-token"))
                    server.enqueue(MockResponse().setBody("remote"))
                    assertEquals(RuleValue.Text("remote"), run("@js:java.ajax('/chapter')"))
                    val token = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("/token", token.path)
                    assertNull(token.getHeader("Authorization"))
                    val chapter = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("/chapter", chapter.path)
                    assertEquals("source-token", chapter.getHeader("Authorization"))
                    assertEquals(2, server.requestCount)
                }
            }
        } finally { executor.close() }
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
                        "source.put('result',java.ajax('/chapter?page={{page}}')); [source.id,source.get('result')]",
                        baseUrl = server.url("/").toString()), limits, broker)
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
                val pending = async { executor.execute(id, ExecutionTask.Script("java.ajax('/slow')", baseUrl = server.url("/").toString()), limits, broker) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                pending.cancel()
                withTimeout(5000) { pending.join() }
                server.enqueue(MockResponse().setBody("next"))
                SourceExecutionBroker(id, authority, session, limits, server.url("/").toString()).use { next ->
                    assertEquals(ExecutionResult.Success("\"next\""), executor.execute(id,
                        ExecutionTask.Script("java.ajax('/next')", baseUrl = server.url("/").toString()), limits, next))
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
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkPermission(android.Manifest.permission.INTERNET, -1, service.workerUid()))
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
