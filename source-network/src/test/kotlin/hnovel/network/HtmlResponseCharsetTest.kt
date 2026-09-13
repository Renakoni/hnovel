package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class HtmlResponseCharsetTest {
    @get:Rule val directory = TemporaryFolder()

    private fun verify(block: suspend (MockWebServer, SourceSession) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { broker ->
                val session = broker.open(SourceScope("charset", "source", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), allowPrivateAddresses = true)))
                block(server, session)
            }
        }
    }

    private suspend fun SourceSession.fetch(server: MockWebServer, override: String? = null, cache: CacheMode = CacheMode.Disabled): BrokerResponse {
        val result = execute(BrokerRequest("charset", server.url("/").toString(), charset = "GB2312",
            responseCharset = override, cache = cache))
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }

    @Test fun htmlDeclarationsDecodeTextWithoutChangingBytesOrHttpMetadata() = verify { server, session ->
        for ((meta, encoding) in listOf(
            "<meta charset='gb18030'>" to "GB18030",
            "<META CONTENT='text/html; charset=gbk' HTTP-EQUIV='Content-Type'>" to "GBK",
            "<meta http-equiv=content-type content=\"text/html; charset=gb2312\">" to "GB2312"
        )) {
            val html = "<html><head>$meta</head><body>校园小说</body></html>"
            val bytes = html.toByteArray(Charset.forName(encoding))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(okio.Buffer().write(bytes)))
            val response = session.fetch(server)
            assertEquals(html, response.text())
            assertEquals(encoding, response.charset)
            assertNull(response.declaredCharset)
            assertArrayEquals(bytes, response.body)
        }
    }

    @Test fun explicitAndHttpCharsetsKeepPriorityOverHtml() = verify { server, session ->
        val html = "<meta charset=Shift_JIS><p>校园小说</p>"
        for ((override, encoding) in listOf(null to "UTF-8", "GB18030" to "GB18030")) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(okio.Buffer().write(html.toByteArray(Charset.forName(encoding)))))
            val response = session.fetch(server, override)
            assertEquals(html, response.text())
            assertEquals(encoding, response.charset)
            assertEquals(encoding, response.declaredCharset)
        }
    }

    @Test fun invalidOrNonDeclarationTextAndLateMetadataLeaveUtf8Unchanged() = verify { server, session ->
        for (prefix in listOf(
            "<meta charset=not-a-charset>",
            "<!-- <meta charset=GBK> --><script>var text='<meta charset=GBK>';</script>",
            "<meta name=description content='text/html; charset=GBK'>",
            "<template><meta charset=GBK></template>",
            " ".repeat(9000) + "<meta charset=GBK>"
        )) {
            val html = "<html><head>$prefix</head><body>校园小说</body></html>"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
            assertEquals(html, session.fetch(server).text())
        }
    }

    @Test fun explicitNonHtmlMediaTypesDoNotInterpretMetadata() = verify { server, session ->
        val text = "<meta charset=GBK>校园小说"
        for (type in listOf("application/json", "text/plain", "application/octet-stream", "application/javascript")) {
            server.enqueue(MockResponse().setHeader("Content-Type", type).setBody(text))
            val response = session.fetch(server)
            assertEquals("UTF-8", response.charset)
            assertEquals(text, response.text())
        }
    }

    @Test fun missingContentTypeAndCacheRetainInferredEncoding() = verify { server, session ->
        val html = "<head><meta charset=GB18030></head><p>校园𠀀</p>"
        server.enqueue(MockResponse().setBody(okio.Buffer().write(html.toByteArray(Charset.forName("GB18030")))))
        val response = session.fetch(server, cache = CacheMode.ReadThrough)
        assertEquals(html, response.text())
        val cached = session.fetch(server, cache = CacheMode.Only)
        assertTrue(cached.fromCache)
        assertEquals(html, cached.text())
        assertNull(cached.declaredCharset)
        assertArrayEquals(response.body, cached.body)
        assertEquals(1, server.requestCount)
    }
}
