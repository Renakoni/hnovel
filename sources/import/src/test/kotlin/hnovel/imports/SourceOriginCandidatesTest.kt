package hnovel.imports

import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceOriginCandidatesTest {
    @Test fun urlPrefixesDoNotCreateUnsubmittableDraftsWhileCompleteHostsSurvive() {
        val raw = buildJsonObject {
            put("jsLib", """url.startsWith("https://210.140"); "https://210.140.92.183:8443/path"; "https://[2001:db8::1]:9443/"; "https://{{host}}/"; "https://books.example/"""")
        }
        assertEquals(listOf(
            OriginCandidate("https://210.140.92.183:8443", ResourceKind.Script),
            OriginCandidate("https://[2001:db8::1]:9443", ResourceKind.Script),
            OriginCandidate("https://books.example:443", ResourceKind.Script)
        ), SourceOriginCandidates.discover(raw))
    }

    @Test fun candidatesRetainPurposeAndStripPathsQueriesAndCredentials() {
        val raw = buildJsonObject {
            put("bookSourceUrl", "https://books.invalid/source?token=secret")
            put("searchUrl", "https://api.invalid:8443/find?q={{key}},{'method':'POST'}")
            put("ruleBookInfo", buildJsonObject { put("coverUrl", "@js:'https://cdn.invalid/private/cover?signature=secret'") })
            put("jsLib", "{\"common\":\"https://scripts.invalid/auth/private.js\"}")
            put("loginUrl", "https://user:password@login.invalid/path")
            put("header", "{\"Cookie\":\"https://credential.invalid/secret\"}")
        }
        assertEquals(listOf(
            OriginCandidate("https://books.invalid:443", ResourceKind.Document),
            OriginCandidate("https://api.invalid:8443", ResourceKind.Api),
            OriginCandidate("https://scripts.invalid:443", ResourceKind.Script),
            OriginCandidate("https://cdn.invalid:443", ResourceKind.Image)
        ), SourceOriginCandidates.discover(raw))
    }

    @Test fun unresolvedHostsAreNotSuggestedAndCandidateCollectionIsBounded() {
        assertTrue(SourceOriginCandidates.discover(buildJsonObject {
            put("searchUrl", "https://{{host}}/search"); put("exploreUrl", "//scheme-dependent.invalid/")
        }).isEmpty())
        val raw = buildJsonObject { put("exploreUrl", (0..99).joinToString(" ") { "https://source-$it.invalid/private?secret=$it" }) }
        val candidates = SourceOriginCandidates.discover(raw)
        assertEquals(32, candidates.size)
        assertEquals(32, candidates.distinct().size)
        assertTrue(candidates.none { "secret" in it.origin || "private" in it.origin })
    }

    @Test fun cleanupPatternsDoNotBecomeWebsitePermissions() {
        val raw = buildJsonObject {
            put("bookSourceUrl", "https://books.invalid/")
            putJsonObject("ruleContent") {
                put("replaceRegex", "https://www.books.invalid.*.html|footer##")
                put("content", "article@text")
            }
        }
        assertEquals(listOf(OriginCandidate("https://books.invalid:443", ResourceKind.Document)), SourceOriginCandidates.discover(raw))
    }
}
