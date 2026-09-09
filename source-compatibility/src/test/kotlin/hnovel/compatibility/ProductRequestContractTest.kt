package hnovel.compatibility

import hnovel.network.CompiledRequest
import hnovel.network.RequestCompiler
import org.junit.Assert.*
import org.junit.Test

/** The existing synthetic source URLs execute through the real static compiler; no website is contacted. */
class ProductRequestContractTest {
    @Test fun syntheticSourceSearchUrlsHaveEncodedKeywordsAndExplicitPages() {
        val compiler = RequestCompiler()
        for (source in FixtureCorpus.json("sources.json").getAsJsonArray("sources")) {
            val definition = source.asJsonObject
            val result = compiler.compile("fixture", definition.string("searchUrl"), definition.string("bookSourceUrl"), "a&b", 2)
            assertTrue(result.toString(), result is CompiledRequest.Ready)
            assertEquals("https://reader.invalid/search?q=a%26b&page=2", (result as CompiledRequest.Ready).request.url)
        }
    }
}
