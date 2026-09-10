package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceBridgeTest {
    @get:Rule val folder = TemporaryFolder()
    private val authority = ExecutionAuthority()
    private fun script(broker: SourceExecutionBroker, code: String, base: String): ExecutionResult {
        val wire = ExecutionWire.encode(broker.identity, ExecutionTask.Script(code, baseUrl=base), broker.limits)
        return ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args ->
            runBlocking { broker.call(name, args) }
        }).toByteArray())
    }

    @Test fun downloadReadDeleteAndCachedImportUseOnlySourceOwnedLogicalPaths() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base=server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session=sessions.open(SourceScope("fixture","a","legado"), listOf(NetworkGrant(base,true)))
                val id=authority.issue("a","legado","1","fixture")
                SourceExecutionBroker(id,authority,session,ExecutionLimits(timeoutMillis=15000,maxRequests=20),base).use { broker ->
                    server.enqueue(MockResponse().setBody("21*2"))
                    assertEquals(ExecutionResult.Success("[\"21*2\",\"21*2\",true,true,true]"), script(broker,"""
                        var p=java.downloadFile('/library.js');
                        var text=java.readTxtFile(p);
                        var imported=java.importScript(p);
                        [text,imported,java.readFile(p).length===4,java.deleteFile(p),java.readFile(p)===null]
                    """,base))
                    server.enqueue(MockResponse().setBody("var answer=42;"))
                    assertEquals(ExecutionResult.Success("true"),script(broker,"java.cacheFile('${base}cached.js')===java.importScript('${base}cached.js')",base))
                    assertEquals(2,server.requestCount)
                    // Expiry starts when the download completes, not before a slow response.
                    server.enqueue(MockResponse().setBody("old").setBodyDelay(1100,java.util.concurrent.TimeUnit.MILLISECONDS))
                    assertEquals(JsonPrimitive("old"),broker.call("java.cacheFile",listOf(JsonPrimitive("${base}short.txt"),JsonPrimitive(1))))
                    assertEquals(JsonPrimitive("old"),broker.call("java.cacheFile",listOf(JsonPrimitive("${base}short.txt"),JsonPrimitive(1))))
                    assertEquals(3,server.requestCount)
                    delay(1100)
                    server.enqueue(MockResponse().setBody("new"))
                    assertEquals(JsonPrimitive("new"),broker.call("java.cacheFile",listOf(JsonPrimitive("${base}short.txt"),JsonPrimitive(1))))
                    assertEquals(4,server.requestCount)
                }
            }
        }
    }

    @Test fun resourcePathsCannotCrossSourceAccountsOrTheFilesystem() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base=server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val first=sessions.open(SourceScope("fixture","a","legado"),listOf(NetworkGrant(base,true)))
                val id=authority.issue("a","legado","1","fixture")
                var path=""
                SourceExecutionBroker(id,authority,first,ExecutionLimits(),base).use { broker ->
                    path=broker.call("java.downloadFile",listOf(JsonPrimitive("4142"),JsonPrimitive("${base}data,{\"type\":\"txt\"}"))).jsonPrimitive.content
                    assertEquals(ExecutionResult.Success("\"AB\""),script(broker,"java.readTxtFile(${JsonPrimitive(path)})",base))
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied),script(broker,"java.readFile('../../host.db')",base))
                }
                for (scope in listOf(SourceScope("fixture","b","legado"),SourceScope("fixture","a","legado",1))) {
                    val session=sessions.open(scope,listOf(NetworkGrant(base,true)))
                    val ticket=authority.issue(scope.sourceId,"legado","1","fixture",scope.accountGeneration)
                    SourceExecutionBroker(ticket,authority,session,ExecutionLimits(),base).use { broker ->
                        assertEquals(JsonNull,broker.call("java.readFile",listOf(JsonPrimitive(path))))
                    }
                }
                assertEquals(0,server.requestCount)
            }
        }
    }

    @Test fun cachedResourcesRecheckOriginGrantAndHonorStorageQuota() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base=server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val scope=SourceScope("fixture","a","legado")
                val first=sessions.open(scope,listOf(NetworkGrant(base,true)))
                var path=""
                server.enqueue(MockResponse().setBody("private"))
                SourceExecutionBroker(authority.issue("a","legado","1","fixture"),authority,first,ExecutionLimits(),base).use { broker ->
                    path=broker.call("java.downloadFile",listOf(JsonPrimitive("/file.txt"))).jsonPrimitive.content
                }
                first.close()
                val restricted=sessions.open(scope,emptyList())
                SourceExecutionBroker(authority.issue("a","legado","1","fixture"),authority,restricted,ExecutionLimits(),base).use { broker ->
                    assertTrue(runCatching { broker.call("java.readTxtFile",listOf(JsonPrimitive(path))) }.isFailure)
                }
                assertEquals(1,server.requestCount)
            }
            SourceBroker(folder.newFolder().toPath(),limits=BrokerLimits(maxStorageBytes=64)).use { sessions ->
                val session=sessions.open(SourceScope("fixture","small","legado"),listOf(NetworkGrant(base,true)))
                SourceExecutionBroker(authority.issue("small","legado","1","fixture"),authority,session,ExecutionLimits(),base).use { broker ->
                    assertTrue(runCatching { broker.call("java.downloadFile",listOf(JsonPrimitive("4142"),JsonPrimitive("${base}data,{\"type\":\"txt\"}"))) }.isFailure)
                }
            }
        }
    }
}
