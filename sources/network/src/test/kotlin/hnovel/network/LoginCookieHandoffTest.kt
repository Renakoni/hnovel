package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoginCookieHandoffTest {
    @get:Rule val directory = TemporaryFolder()
    private val scope = SourceScope("test", "login-handoff", "legado")
    private val login = StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS)

    private fun SourceSession.seed(base: String) {
        configureSource(base, true)
        write(login.copy(value = """{"Cookie":"auth=old; theme=dark","Authorization":"Bearer keep"}"""))
        setCookie(base, "auth=old; theme=dark")
    }

    @Test fun browserRotationRetiresOnlyStaleLoginCookiesAndPreservesRequestOverrides() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(
                    if (request.getHeader("Cookie").orEmpty().contains("auth=fresh")) 200 else 401)
            }
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val base = server.url("/").toString()
                val session = broker.open(scope, listOf(NetworkGrant(base, true))).apply { seed(base) }
                suspend fun send(headers: Map<String, String> = emptyMap()): Int =
                    (session.execute(BrokerRequest("read", base, headers = headers)) as BrokerResult.Success).response.status
                assertEquals(401, send())
                server.takeRequest()
                val seed = session.nativeBrowserCookieSeed(base)
                session.updateNativeBrowserCookies(base, listOf("auth=fresh; Path=/; HttpOnly", "theme=dark; Path=/"),
                    expectedSeedVersion = seed.version)
                assertEquals(200, send())
                val received = server.takeRequest()
                assertEquals("Bearer keep", received.getHeader("Authorization"))
                assertTrue(received.getHeader("Cookie").orEmpty().contains("theme=dark"))
                assertFalse((session.read(login) as StorageResult.Value).value!!.contains("auth=old"))
                assertEquals(401, send(mapOf("Cookie" to "auth=pinned")))
                assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("auth=pinned"))
            }
        }
    }

    @Test fun httpRotationAndDeletionDoNotResurrectPersistedLoginCookies() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            val grants = listOf(NetworkGrant(base, true))
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(scope, grants).apply { seed(base) }
                server.enqueue(MockResponse().addHeader("Set-Cookie", "auth=fresh; Path=/; HttpOnly"))
                session.execute(BrokerRequest("rotate", base))
                assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("auth=old"))
                server.enqueue(MockResponse().addHeader("Set-Cookie", "auth=; Path=/; Max-Age=0"))
                session.execute(BrokerRequest("delete", base))
                assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("auth=fresh"))
            }
            SourceBroker(directory.root.toPath()).use { broker ->
                val restored = broker.open(scope, grants).apply { configureSource(base, true) }
                server.enqueue(MockResponse())
                restored.execute(BrokerRequest("restored", base))
                assertFalse(server.takeRequest().getHeader("Cookie").orEmpty().contains("auth="))
            }
        }
    }

    @Test fun staleBrowserSnapshotsAndPathCookiesDoNotRetireCurrentLoginHeaders() {
        SourceBroker(directory.root.toPath()).use { broker ->
            val base = "https://source.example/"
            val session = broker.open(scope, listOf(NetworkGrant(base))).apply { seed(base) }
            val seed = session.nativeBrowserCookieSeed(base)
            session.updateNativeBrowserCookies(base + "private/", listOf(
                "auth=old; Path=/", "theme=dark; Path=/", "auth=private; Path=/private/"), expectedSeedVersion = seed.version)
            assertTrue((session.read(login) as StorageResult.Value).value!!.contains("auth=old"))
            session.write(login.copy(value = """{"cookie":"auth=new-login"}"""))
            session.setCookie(base, "auth=new-login")
            session.updateNativeBrowserCookies(base, listOf("auth=stale; Path=/"), expectedSeedVersion = seed.version)
            assertEquals("""{"cookie":"auth=new-login"}""", (session.read(login) as StorageResult.Value).value)
            assertTrue(session.cookie(base).contains("auth=new-login"))
            session.updateNativeBrowserCookies(base, emptyList(), expectedSeedVersion = session.nativeBrowserCookieSeed(base).version)
            assertEquals("{}", (session.read(login) as StorageResult.Value).value)
        }
    }

    @Test fun savedLoginCookiesUseBrowserStoreButExplicitRequestCookiesRemainExplicit() = runBlocking {
        val seen = mutableListOf<Map<String, String>>()
        val browser = BrowserExecutor { _, request, _, _, _ ->
            seen += request.headers
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), byteArrayOf(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        }
        SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
            val base = "https://source.example/"
            val session = broker.open(scope, listOf(NetworkGrant(base))).apply { seed(base) }
            session.execute(BrokerRequest("browser", base, browser = BrowserOptions()))
            assertFalse(seen.last().keys.any { it.equals("Cookie", true) })
            assertEquals("Bearer keep", seen.last()["Authorization"])
            assertTrue(session.nativeBrowserCookieSeed(base).cookies.any { it.startsWith("auth=old;") })
            session.execute(BrokerRequest("explicit", base, headers = mapOf("Cookie" to "auth=pinned"), browser = BrowserOptions()))
            assertEquals("auth=pinned", seen.last()["Cookie"])
        }
    }
}
