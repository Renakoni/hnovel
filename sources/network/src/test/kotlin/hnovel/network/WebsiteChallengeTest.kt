package hnovel.network

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files

class WebsiteChallengeTest {
    @Test fun rawLoginApiAndBrowserSubrequestsRemainReadableWhileDocumentExtractionRequiresVerification() = runBlocking {
        val root = Files.createTempDirectory("http-challenge")
        try { MockWebServer().use { server ->
            server.start()
            val html = "<html><title>Verify Yourself</title><form id='ui-form'>Slide to Unlock</form></html>"
            repeat(3) { server.enqueue(MockResponse().setBody(html)) }
            SourceBroker(root).use { broker ->
                val session = broker.open(SourceScope("fixture", "challenge", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val request = BrokerRequest("toc", server.url("/WAF/VERIFY/CAPTCHA").toString())
                assertEquals(html, (session.execute(request.copy(kind = ResourceKind.Api)) as BrokerResult.Success).response.text())
                assertEquals(html, (session.executeHttp(request, RequestCommitGuard { it() }) as BrokerResult.Success).response.text())
                assertEquals(BrowserChallengeKind.SiteVerification, (session.execute(request) as BrokerResult.Failure).challenge)
            }
        } } finally { root.toFile().deleteRecursively() }
    }

    private fun response(path: String, html: String, headers: Map<String, List<String>> = emptyMap()) =
        BrokerResponse(200, "https://fixture.example$path", headers, html.toByteArray(), "UTF-8", 0)

    @Test fun recognizesSuccessfulStatusVerificationPagesWithoutMatchingChapterProse() {
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/WAF/VERIFY/CAPTCHA?from=/toc?page=14",
            "<html><title>Verify Yourself</title><form id='ui-form'>Slide to Unlock</form></html>")))
        assertEquals(BrowserChallengeKind.Cloudflare, websiteChallenge(response("/book", "<title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/test'></script>")))
        assertEquals(BrowserChallengeKind.Cloudflare, websiteChallenge(response("/api", "", mapOf("CF-Mitigated" to listOf("challenge")))))
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/antibot", "<html>Verification</html>")))
        assertEquals(BrowserChallengeKind.Login, websiteChallenge(response("/login", "<form><input type='password'></form>")))
        assertNull(websiteChallenge(response("/chapter", "<title>Verify Yourself</title><article>Slide to Unlock, CAPTCHA and Just a moment</article>")))
        assertNull(websiteChallenge(response("/chapter", "<title>Just a moment</title><article>A normal chapter</article>")))
        assertNull(websiteChallenge(response("/api", """{"content":"<form id='J_ManMachineVerify'>sample</form>"}""")))
        val gbk = "<html><title>人机校验</title><form>请验证</form></html>".toByteArray(charset("GB18030"))
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/book", "").copy(body = gbk, charset = "GB18030")))
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/book", "\uFEFF<html><title>人机校验</title></html>")))
    }
}
