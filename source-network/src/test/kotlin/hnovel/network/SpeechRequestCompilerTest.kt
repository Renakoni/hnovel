package hnovel.network

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SpeechRequestCompilerTest {
    private val text = "中文 English \" ' \\ &?\n{{key}} {{speakSpeed}} <speak>"

    @Test fun speechTextIsEncodedAsOneQueryOrFormValueAndNeverExpandedAgain() {
        val compiler = RequestCompiler()
        val get = compiler.compile("speech", "https://example.org/?text={{speakText}}&speed={{speakSpeed}}", "",
            speakText = text, speakSpeed = 10) as CompiledRequest.Ready
        assertEquals(text, get.request.url.toHttpUrl().queryParameter("text"))
        assertEquals("10", get.request.url.toHttpUrl().queryParameter("speed"))
        val post = compiler.compile("speech", """https://example.org/,{"method":"POST","body":"text={{speakText}}&speed={{speakSpeed}}"}""", "",
            speakText = text) as CompiledRequest.Ready
        assertEquals(text, ("https://example.org/?" + post.request.body).toHttpUrl().queryParameter("text"))
    }

    @Test fun speechTextRemainsJsonDataEvenWhenItContainsQuotesAndTemplateSyntax() {
        val request = RequestCompiler().compile("speech", """https://example.org/,{"method":"POST","body":{"text":"{{speakText}}","speed":{{speakSpeed}}}}""", "",
            speakText = text, speakSpeed = 15) as CompiledRequest.Ready
        val body = Json.parseToJsonElement(request.request.body!!).jsonObject
        assertEquals(text, body.getValue("text").jsonPrimitive.content)
        assertEquals(15, body.getValue("speed").jsonPrimitive.int)
    }

    @Test fun speechVariablesRequireAnExplicitSpeechInvocationAndBoundedInput() {
        val compiler = RequestCompiler()
        assertEquals(CompiledRequest.Rejected(FailureCode.ScriptRequired), compiler.compile("ordinary", "https://example.org/?text={{speakText}}", ""))
        assertEquals(CompiledRequest.Rejected(FailureCode.InvalidRequest), compiler.compile("speech", "https://example.org/", "", speakText = "x".repeat(65537)))
        assertEquals(CompiledRequest.Rejected(FailureCode.InvalidRequest), compiler.compile("speech", "https://example.org/", "", speakText = "text", speakSpeed = 86))
    }

    @Test fun speechExpressionResultsDoNotBecomePageAlternatives() {
        val request = RequestCompiler().compile("speech", "https://example.org/?text={{speechExpression0}}", "",
            speakText = "<first,second>", templateValues = mapOf("speechExpression0" to "<first,second>")) as CompiledRequest.Ready
        assertEquals("<first,second>", request.request.url.toHttpUrl().queryParameter("text"))
    }
}
