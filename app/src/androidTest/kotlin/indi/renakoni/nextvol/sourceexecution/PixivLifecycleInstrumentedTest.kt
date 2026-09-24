package indi.renakoni.nextvol.sourceexecution

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.*
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.di.WebDataSourceModule
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Synthetic contracts, not a replay of Pixiv responses and not evidence of live-site readability. */
@RunWith(AndroidJUnit4::class)
class PixivLifecycleInstrumentedTest {
    @Test fun loginSingleSeriesOrderedContentAndProgressSurviveReconstructionButLogoutRetiresTheAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/login") {
                        val account = request.body.readUtf8().removePrefix("user=")
                        require(account in setOf("alice", "bob"))
                        // The server explicitly persists this login; session cookies stay memory-only.
                        return MockResponse().setHeader("Set-Cookie", "account=$account; Path=/; Max-Age=3600").setBody("ok")
                    }
                    val cookie = request.getHeader("Cookie").orEmpty()
                    val account = when { cookie.contains("account=alice") -> "alice"; cookie.contains("account=bob") -> "bob"; else -> return MockResponse().setResponseCode(401) }
                    val body = when (request.path?.substringBefore('?')) {
                        "/search" -> "<li><a href='/single'><h2>Standalone</h2></a></li><li><a href='/novel/1'><h2>Member</h2></a></li>"
                        "/single" -> "<h1>Standalone</h1><li><a href='/single'>Single chapter</a></li><article>before-$account<img src='/image.png'>after-$account</article>"
                        "/series/10" -> "<h1>Series</h1><li><a href='/novel/1'>One</a></li><li><a href='/novel/2'>Two</a></li>"
                        "/novel/1", "/novel/2" -> "<h1>Member</h1><article>before-$account<img src='/image.png'>after-$account</article>"
                        "/slow" -> return MockResponse().setBody("<h1>Slow</h1>").setBodyDelay(5, TimeUnit.SECONDS)
                        "/image.png" -> return MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3)))
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
                }
            }
            val root = File(context.cacheDir, "pixiv-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
            val preferences = "pixiv-lifecycle-${UUID.randomUUID()}"
            val host = object : ContextWrapper(context) {
                override fun getFilesDir() = root
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences + name, mode)
            }
            var authority = ExecutionAuthority()
            var registry = WebSourceRegistry(authority)
            var accounts = SourceSessionManager(authority, WebDataSourceModule.provideSourceSessionEpochStore(host))
            var executor = AndroidIsolatedExecutor(host, authority)
            fun service() = ImportedRuleSources(host, registry, authority, accounts, WebDataSourceModule.provideRuleTaskRunner(executor),
                storageCipher = AndroidSourceStorageCipher())
            var sources = service()
            fun openDb() = Room.databaseBuilder(host, NextVolDatabase::class.java, File(root, "library.db").absolutePath).build()
            var db = openDb()
            fun reading() = BookReadingDataRepository(LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(),
                db.chapterContentDao(), db.userReadingDataDao(), BookAliasStore(db)))
            val origin = fixture.server.url("/").toString().removeSuffix("/")
            val raw = JsonObject(fixture.raw() + mapOf(
                "enabledCookieJar" to JsonPrimitive(true),
                "loginUrl" to JsonPrimitive("function login(){java.post('$origin/login','user='+source.getLoginInfoMap().get('user'),{'Content-Type':'application/x-www-form-urlencoded'});}"),
                "loginUi" to JsonPrimitive("""[{"name":"user"}]"""),
                "exploreUrl" to JsonPrimitive("""@js:JSON.stringify([{title:'Fixture',url:'/search'}])"""),
                "ruleBookInfo" to buildJsonObject {
                    put("init", "@js:if(String(book.bookUrl).indexOf('/novel/')>=0)book.bookUrl='$origin/series/10';result")
                    put("name", "h1@text"); put("tocUrl", "@js:book.bookUrl")
                },
                "ruleToc" to buildJsonObject { put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href") },
                "ruleContent" to buildJsonObject { put("content", "article@html") },
            ))
            try {
                val preview = sources.importer.preview(raw.toString(), AUTO_PROFILE)
                assertTrue(preview.issues.isEmpty())
                val saved = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(origin, true)))
                var login = SourceLoginService(sources, accounts)
                var rules = sources.loginTarget(id).rules
                assertTrue(rules.openDiscovery("fixture").catalog().rows.isNotEmpty())
                val unauthenticated = runCatching { rules.search("fixture") }.exceptionOrNull() as SourceContentException
                // A search 401 may be access policy, not proof that submitted credentials failed.
                assertEquals(ContentError.Network, unauthenticated.code)
                assertEquals("searchUrl", unauthenticated.field)
                suspend fun signIn(user: String) {
                    val panel = login.begin(id)
                    try { login.form(panel); login.submit(panel, mapOf("user" to user)) } finally { login.cancel(panel) }
                }
                signIn("alice")
                val generation = accounts.current(id).generation
                val panel = login.begin(id); login.form(panel); login.cancel(panel)
                assertEquals(generation, accounts.current(id).generation)
                assertEquals(2, rules.search("fixture").size)
                val single = "$origin/single"
                assertEquals(single, rules.information(single).id)
                assertEquals(single, rules.directory(single).single().id)
                val member = "$origin/novel/1"
                val series = "$origin/series/10"
                assertEquals(series, rules.information(member).id)
                assertEquals(listOf(member, "$origin/novel/2"), rules.directory(member).map { it.id })
                suspend fun ordered(book: String, chapter: String, user: String) {
                    val parts = rules.content(book, chapter).parts
                    assertEquals(listOf("before-$user", null, "after-$user"), parts.map { it.text })
                    assertEquals(listOf(null, "$origin/image.png", null), parts.map { it.image })
                    assertArrayEquals(byteArrayOf(1, 2, 3), rules.image(book, "$origin/image.png", false))
                }
                ordered(single, single, "alice"); ordered(member, member, "alice")
                val book = SourceBookId(id, series)
                val chapter = SourceChapterId(book, member)
                reading().updateUserReadingData(book.storageKey) { it.copy(lastReadChapterId = chapter.storageKey,
                    currentChapterReadingProgressMap = mapOf(chapter.storageKey to .4f)) }
                val originalSession = sources.loginTarget(id).session
                originalSession.write(StorageRequest(StorageArea.Account, "fixture-private", "alice-only"))
                originalSession.write(StorageRequest(StorageArea.Cache, "fixture-cache", "alice-only"))
                sources.stop(); executor.close(); db.close()
                authority = ExecutionAuthority(); registry = WebSourceRegistry(authority)
                accounts = SourceSessionManager(authority, WebDataSourceModule.provideSourceSessionEpochStore(host))
                executor = AndroidIsolatedExecutor(host, authority)
                sources = service(); sources.restore(); db = openDb()
                login = SourceLoginService(sources, accounts); rules = sources.loginTarget(id).rules
                assertEquals(generation, accounts.current(id).generation)
                assertEquals(.4f, reading().getUserReadingData(book.storageKey).currentChapterReadingProgressMap[chapter.storageKey])
                assertEquals(chapter.storageKey, reading().getUserReadingData(book.storageKey).lastReadChapterId)
                val restored = sources.loginTarget(id).session
                assertEquals(StorageResult.Value("alice-only"), restored.read(StorageRequest(StorageArea.Account, "fixture-private")))
                assertEquals(StorageResult.Value("alice-only"), restored.read(StorageRequest(StorageArea.Cache, "fixture-cache")))
                ordered(member, member, "alice")
                assertTrue(runCatching { withTimeout(100) { rules.information("$origin/slow") } }.exceptionOrNull() is CancellationException)
                assertEquals(2, rules.search("after-cancel").size)
                val oldPanel = login.begin(id)
                login.logout(id)
                val stale = runCatching { login.submit(oldPanel, mapOf("user" to "alice")) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Unavailable, stale.code)
                login.cancel(oldPanel)
                val fresh = sources.loginTarget(id)
                assertEquals(StorageResult.Value(null), fresh.session.read(StorageRequest(StorageArea.Account, "fixture-private")))
                assertEquals(StorageResult.Value(null), fresh.session.read(StorageRequest(StorageArea.Cache, "fixture-cache")))
                assertFalse(fresh.session.cookie(origin).contains("alice"))
                rules = fresh.rules
                assertEquals(ContentError.Network, (runCatching { rules.search("logged-out") }.exceptionOrNull() as SourceContentException).code)
                signIn("bob"); ordered(member, member, "bob")
            } finally {
                sources.stop(); executor.close(); db.close(); root.deleteRecursively()
                host.getSharedPreferences("source_account_generations", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
}
