package indi.dmzz_yyhyy.lightnovelreader.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.ContentError
import hnovel.content.RuleSourceFixture
import hnovel.content.SourceContentException
import hnovel.imports.*
import hnovel.network.*
import indi.dmzz_yyhyy.lightnovelreader.data.web.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceLoginServiceTest {
    @Test fun browserLoginNetworkFailurePreservesStatusWhileHttpAuthenticationFailureRequiresLogin() = runBlocking {
        val root = Files.createTempDirectory("browser-login-status").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        val browser = BrowserExecutor { session, request, options, guard ->
            assertTrue(options.interactive)
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, browser = browser)
            val login = SourceLoginService(sources, accounts)
            try {
                val raw = JsonObject(fixture.raw() +
                    ("loginUrl" to JsonPrimitive(fixture.server.url("/book/one").toString())))
                val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
                assertNull(saved.error)
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val attempt = login.begin(id)
                fixture.status = 503
                val network = runCatching { login.submit(attempt, emptyMap()) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Network, network.code)
                assertEquals(LoginStatus.LoggedOut, login.status(id))
                fixture.status = 401
                val authentication = runCatching { login.submit(attempt, emptyMap()) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.LoginRequired, authentication.code)
                assertEquals(LoginStatus.Required, login.status(id))
                fixture.status = 200
                login.submit(attempt, emptyMap())
                assertEquals(LoginStatus.Authenticated, login.status(id))
                fixture.status = 503
                assertEquals(ContentError.Network,
                    (runCatching { login.submit(attempt, emptyMap()) }.exceptionOrNull() as SourceContentException).code)
                assertEquals(LoginStatus.Authenticated, login.status(id))
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }

    @Test fun twoSourcesShareAnOriginButNotAccountsAndLogoutPurgesOnlyItsOwnSecrets() = runBlocking {
        val root = Files.createTempDirectory("login-host").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/login") {
                        val user = request.body.readUtf8().substringAfter("user=")
                        return MockResponse().setBody("ok").addHeader("Set-Cookie", "sid=$user; Path=/; Max-Age=3600")
                    }
                    request.getHeader("X-Source")?.let { seen[it] = request.getHeader("Cookie").orEmpty() }
                    return original.dispatch(request)
                }
            }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val login = SourceLoginService(sources, accounts)
            try {
                suspend fun install(label: String): io.nightfish.lightnovelreader.api.identifier.Identifier {
                    val raw = JsonObject(fixture.raw(label) + mapOf(
                        "loginUi" to JsonPrimitive("[{\"name\":\"user\"},{\"name\":\"password\",\"type\":\"password\"}]"),
                        "loginUrl" to JsonPrimitive("""function login(){var info=source.getLoginInfoMap();
                            var response=java.post(baseUrl.substring(0,baseUrl.lastIndexOf('/')+1)+'login','user='+info.get('user'),{'Content-Type':'application/x-www-form-urlencoded'});
                            if(response.statusCode()!==200)throw new Error('login failed');
                            source.put('saved','note-'+info.get('user'));
                            cache.put('saved','cached-'+info.get('user'),3600);
                            source.putLoginHeader(JSON.stringify({'Authorization':'Bearer '+info.get('password')}));} """.trimIndent())
                    ))
                    val preview = sources.importer.preview(raw.toString())
                    assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                    val result = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                    assertNull(result.error)
                    return sources.activate(result.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                }
                val a = install("A"); val b = install("B")
                val attemptA = login.begin(a); val attemptB = login.begin(b)
                login.submit(attemptA, mapOf("user" to "alice", "password" to "alice-secret"))
                login.submit(attemptB, mapOf("user" to "bob", "password" to "bob-secret"))
                for (id in listOf(a, b)) {
                    assertEquals(LoginStatus.Authenticated, login.status(id))
                    assertTrue((registry.resolve(id) as SourceResolution.Ready).runtime.getBookInformation(fixture.server.url("/book/one").toString()).isOk)
                }
                assertEquals("sid=alice", seen["A"])
                assertEquals("sid=bob", seen["B"])
                val old = sources.loginTarget(a)
                val bookId = fixture.server.url("/book/one").toString()
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val record = old.session.read(StorageRequest(StorageArea.Config, "content/book/$digest")) as StorageResult.Value
                assertNotNull(record.value)
                login.logout(a)
                assertTrue(old.session.closed)
                assertEquals(LoginStatus.LoggedOut, login.status(a))
                assertEquals(LoginStatus.Authenticated, login.status(b))
                val fresh = sources.loginTarget(a).session
                assertEquals(StorageResult.Value(null), fresh.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)))
                assertEquals(StorageResult.Value("note-alice"), fresh.read(StorageRequest(StorageArea.Config, "value:saved")))
                assertEquals(StorageResult.Value("cached-alice"), fresh.read(StorageRequest(StorageArea.Cache, "value:saved")))
                assertEquals(record, fresh.read(StorageRequest(StorageArea.Config, "content/book/$digest")))
                assertEquals(StorageResult.Value(null), fresh.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS)))
                assertEquals("", fresh.cookie(fixture.server.url("/").toString()))
                assertTrue(runCatching { login.submit(attemptA, mapOf("user" to "alice")) }.isFailure)
                // The test cipher is plain: scanning account files proves actual old-account deletion.
                assertFalse(File(root, "rule-sources/runtime").walkTopDown().filter { it.isFile }.any { it.readText().contains("alice-secret") })
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }
}
