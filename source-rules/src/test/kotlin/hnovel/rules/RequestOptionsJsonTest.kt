package hnovel.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RequestOptionsJsonTest {
    @Test fun jsonNumbersBooleansAndNullRemainTypedData() {
        val values = RequestOptionsJson.parse("[0,-0,12,-12,1.5,1e3,1E-2,true,false,null]").jsonArray
        assertEquals(listOf("0", "-0", "12", "-12", "1.5", "1e3", "1E-2", "true", "false", "null"),
            values.map { it.jsonPrimitive.content })
        assertTrue(values.all { !it.jsonPrimitive.isString })
    }

    @Test fun quotedBracketsAndEscapedQuotesAreStringData() {
        val text = "'" + "[{".repeat(100) + "\\\\\\'\\\"\\n\\u4e2d'"
        assertEquals(JsonPrimitive("[{".repeat(100) + "\\'\"\n中"), RequestOptionsJson.parse(text))
        assertEquals(JsonPrimitive("a'b"), RequestOptionsJson.parse("\"a'b\""))
    }

    @Test fun bothQuoteStylesPreserveTheDepthAndCharacterBounds() {
        for (quote in listOf("'", "\"")) {
            val nested = "[".repeat(63) + "{$quote" + "key$quote:0}" + "]".repeat(63)
            assertTrue(RequestOptionsJson.parse(nested) is JsonArray)
            assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.parse("[$nested]") }
        }
        assertEquals(JsonPrimitive("x".repeat(65534)), RequestOptionsJson.parse("'" + "x".repeat(65534) + "'"))
        assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.parse("'" + "x".repeat(65535) + "'") }
        assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.parse("'" + "\"".repeat(32768) + "'") }
    }

    @Test fun nestedHeaderAndBodyStringsCannotBypassTheDepthLimit() {
        val nested = "[".repeat(2000) + "0" + "]".repeat(2000)
        for (field in listOf("body", "header", "headers")) {
            val options = buildJsonObject { put(field, nested) }.toString()
            assertThrows(RequestOptionsException::class.java) { RequestOptionsJson.options(options) }
        }
    }

    @Test fun malformedDataHasOnlyARedactedDiagnostic() {
        for (text in listOf("{'secret':'unterminated}", "{'secret':'\\x41'}", "{'secret':1,}", "{'secret':function(){}}",
            "{secret:'value'}", "{'secret':unquoted}", "{'secret':NaN}", "{'secret':01}",
            "{'secret':unquoted,'secret':0}", "{'secret':1}\u0001",
            "{'secret':1} trailing", "{'secret':'line\nbreak'}")) {
            val error = assertThrows(text, RequestOptionsException::class.java) { RequestOptionsJson.parse(text) }
            assertEquals("Invalid request options", error.message)
            assertNull(error.cause)
        }
    }
}
