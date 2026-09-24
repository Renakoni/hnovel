package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoginInfoTest {
    @get:Rule val folder = TemporaryFolder()
    private val novel = """ {"user":"alice","id":7,"liked":true,"tags":["one"],"pollData":{"votes":[1,2]},"seriesNavData":null} """

    @Test fun rawBusinessDataSurvivesRestartButNotAccountRotationOrAnotherSource() = runBlocking {
        val scope = SourceScope("fixture", "a", "legado")
        suspend fun read(sessions: SourceBroker, current: SourceScope): JsonElement {
            val authority = ExecutionAuthority()
            val id = authority.issue(current.sourceId, current.profile, "1", current.namespace, current.accountGeneration)
            return SourceExecutionBroker(id, authority, sessions.open(current, emptyList()), ExecutionLimits()).use {
                it.call("source.getLoginInfo", emptyList())
            }
        }
        SourceBroker(folder.root.toPath()).use { sessions ->
            val authority = ExecutionAuthority()
            val id = authority.issue("a", "legado", "1", "fixture")
            val session = sessions.open(scope, emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { bridge ->
                assertEquals(JsonPrimitive(true), bridge.call("source.putLoginInfo", listOf(JsonPrimitive(novel))))
                assertEquals(JsonPrimitive(novel), bridge.call("source.getLoginInfo", emptyList()))
                assertEquals(buildJsonObject { put("user", "alice") }, bridge.call("source.getLoginInfoMap", emptyList()))
                for (invalid in listOf("{broken", "[1,", "[".repeat(65) + "0" + "]".repeat(65), "文".repeat(22000))) {
                    assertTrue(runCatching { bridge.call("source.putLoginInfo", listOf(JsonPrimitive(invalid))) }.isFailure)
                    assertEquals(JsonPrimitive(novel), bridge.call("source.getLoginInfo", emptyList()))
                }
            }
        }
        SourceBroker(folder.root.toPath()).use { sessions ->
            assertEquals(JsonPrimitive(novel), read(sessions, scope))
            assertEquals(JsonNull, read(sessions, scope.copy(sourceId = "b")))
            assertEquals(JsonNull, read(sessions, scope.copy(profile = "extended")))
            val authority = ExecutionAuthority()
            val id = authority.issue("a", "legado", "1", "fixture")
            SourceExecutionBroker(id, authority, sessions.open(scope, emptyList()), ExecutionLimits()).use { retired ->
                assertEquals(JsonNull, read(sessions, scope.copy(accountGeneration = 1)))
                assertTrue(runCatching { retired.call("source.putLoginInfo", listOf(JsonPrimitive(novel))) }.isFailure)
            }
        }
    }

    @Test fun opaqueAndNonObjectDataHaveNoMapAndHeadersRemainStrict() = runBlocking {
        SourceBroker(folder.root.toPath()).use { sessions ->
            val authority = ExecutionAuthority()
            val id = authority.issue("a", "legado", "1", "fixture")
            SourceExecutionBroker(id, authority, sessions.open(SourceScope("fixture", "a", "legado"), emptyList()), ExecutionLimits(maxRequests = 64)).use { bridge ->
                for (text in listOf("opaque business text", "[1,{},true]", "null", "42", "\"text\"")) {
                    bridge.call("source.putLoginInfo", listOf(JsonPrimitive(text)))
                    assertEquals(JsonPrimitive(text), bridge.call("source.getLoginInfo", emptyList()))
                    assertEquals(JsonNull, bridge.call("source.getLoginInfoMap", emptyList()))
                }
                val header = """{"Authorization":"Bearer synthetic"}"""
                bridge.call("source.putLoginHeader", listOf(JsonPrimitive(header)))
                for (invalid in listOf(novel, """{"X":1}""", """{"bad name":"x"}""", """{"X":"a\r\nb"}""")) {
                    assertTrue(runCatching { bridge.call("source.putLoginHeader", listOf(JsonPrimitive(invalid))) }.isFailure)
                    assertEquals(JsonPrimitive(header), bridge.call("source.getLoginHeader", emptyList()))
                }
            }
        }
    }
}
