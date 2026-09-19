package hnovel.imports

import hnovel.network.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceImportDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val definition = """{"bookSourceUrl":"identity#fragment","bookSourceName":"Offline","jsLib":"throw 'not executed'"}"""

    @Test fun downloadUsesBrokerAndRetainsOriginalAndFinalLocations() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(temp.newFolder().toPath()).use { broker ->
                val session = broker.open(SourceScope("import", "preview", "json"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val store = SourceDefinitionStore(temp.newFolder().toPath())
                val importer = SourceDefinitionImporter(store)
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/actual.json"))
                server.enqueue(MockResponse().setBody(definition))
                val url = server.url("/redirect").toString()
                val preview = importer.previewUrl(url, session)
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                assertEquals(2, server.requestCount)
                assertEquals("identity#fragment", preview.candidates.single().importKey)
                importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                assertEquals(ImportOrigin(ImportOrigin.Kind.Url, url, server.url("/actual.json").toString()), store.list().single().origin)
            }
        }
    }

    @Test fun rejectedRedirectStatusSizeAndPackagesCannotProduceCandidates() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            SourceBroker(temp.newFolder().toPath(), limits = BrokerLimits(maxResponseBytes = 128)).use { broker ->
                val session = broker.open(SourceScope("import", "preview", "json"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()))
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/private.json")))
                val denied = importer.previewUrl(server.url("/").toString(), session)
                assertEquals(ImportCode.DownloadFailed, denied.issues.single().code)
                assertEquals("OriginDenied", denied.issues.single().field)
                assertEquals(0, other.requestCount)
                server.enqueue(MockResponse().setResponseCode(503).setBody(definition))
                assertEquals(ImportCode.DownloadFailed, importer.previewUrl(server.url("/").toString(), session).issues.single().code)
                server.enqueue(MockResponse().setBody("x".repeat(129)))
                assertEquals("ResponseTooLarge", importer.previewUrl(server.url("/").toString(), session).issues.single().field)
                server.enqueue(MockResponse().setBody("PK\u0003\u0004rest"))
                assertEquals(ImportCode.PluginPackage, importer.previewUrl(server.url("/hidden.json").toString(), session).issues.single().code)
                val count = server.requestCount
                assertEquals(ImportCode.PluginPackage, importer.previewUrl(server.url("/plugin.lnrp").toString(), session).issues.single().code)
                assertEquals(count, server.requestCount)
            }
        } }
    }

    @Test fun privateAddressRequiresHostGrantAndCancellationIsNotHidden() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(temp.newFolder().toPath()).use { broker ->
                val session = broker.open(SourceScope("import", "preview", "json"), listOf(NetworkGrant(server.url("/").toString())))
                val importer = SourceDefinitionImporter(SourceDefinitionStore(temp.newFolder().toPath()))
                assertEquals("AddressDenied", importer.previewUrl(server.url("/").toString(), session).issues.single().field)
                assertEquals(0, server.requestCount)
                session.close()
                assertThrows(CancellationException::class.java) { runBlocking { importer.previewUrl(server.url("/").toString(), session) } }
                Unit
            }
        }
    }
}
