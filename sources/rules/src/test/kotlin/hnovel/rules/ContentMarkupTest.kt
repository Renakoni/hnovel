package hnovel.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ContentMarkupTest {
    private fun parts(html: String): List<JsonObject> {
        val result = ContentMarkup.evaluate(html)
        assertTrue(result.toString(), result is RuleResult.Success)
        return ((result as RuleResult.Success).value as RuleValue.Items).values.map {
            Json.parseToJsonElement((it as RuleValue.Node).content).jsonObject
        }
    }

    @Test fun mixedMarkupPreservesParagraphsInlineSpacingAndImagePositions() {
        val result = parts("""
            <div>  Alpha <em>bold</em> &amp; tail  <p>第一<br>第二</p>
            <img src='../image.png?a=1&amp;b=2'>after<img><p>final</p>
            <script>java.ajax('/untrusted')</script><style>body { color: red }</style>
            <noscript><p>hidden</p></noscript></div>
        """.trimIndent())
        assertEquals(listOf("Alpha bold & tail", "第一", "第二", null, "after", "final"),
            result.map { it["text"]?.jsonPrimitive?.content })
        assertEquals("../image.png?a=1&b=2", result[3].getValue("image").jsonPrimitive.content)
    }

    @Test fun escapedMarkupRemainsTextAndUnicodeBoundaryWhitespaceIsTrimmed() {
        assertEquals(listOf("<b>literal</b>  inside", "line\rinside"),
            parts("<p>\uFEFF\u00A0&lt;b&gt;literal&lt;/b&gt;  inside\u3000</p><p>line&#13;inside</p>")
                .map { it.getValue("text").jsonPrimitive.content })
        assertTrue(parts("<div> \n<br><!-- ignored --><script>while(true){}</script></div>").isEmpty())
    }

    @Test fun deepDomAndExcessiveNodesFailWithTheOriginalFieldLocation() {
        val location = RuleLocation("ruleContent.parts", 7)
        for ((html, code) in listOf(
            "<div>".repeat(70) + "text" + "</div>".repeat(70) to "MarkupDepthLimit",
            "<b></b>".repeat(16_400) to "MarkupNodeLimit",
        )) {
            assertEquals(RuleResult.Failure(RuleError(RuleStage.Budget, location, code)),
                ContentMarkup.evaluate(html, location))
        }
    }

    @Test fun inputOutputAndOperationBudgetsRemainBounded() {
        fun failure(html: String, limits: RuleLimits) =
            (ContentMarkup.evaluate(html, budget = RuleBudget(limits)) as RuleResult.Failure).error.code
        assertEquals("MarkupInputLimit", failure("<p>normal</p>", RuleLimits(maxInputChars = 5)))
        assertEquals("MarkupOutputLimit", failure("<p>normal</p>", RuleLimits(maxOutputChars = 4)))
        assertEquals("BudgetExceeded", failure("<p>normal</p>", RuleLimits(maxSteps = 1)))
    }

    @Test fun interruptedConversionDoesNotClearTheCancellationSignal() {
        Thread.currentThread().interrupt()
        try {
            val result = ContentMarkup.evaluate("<p>normal</p>") as RuleResult.Failure
            assertEquals(RuleStage.Budget, result.error.stage)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
