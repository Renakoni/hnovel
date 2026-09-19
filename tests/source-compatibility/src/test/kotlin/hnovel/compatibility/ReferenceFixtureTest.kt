package hnovel.compatibility

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReferenceFixtureTest(private val id: String, private val case: JsonObject) {
    @Test(timeout = 5000)
    fun referenceMatchesReviewedExpectation() {
        val expectedError = case.get("expectedError")?.asString
        if (expectedError != null) {
            val failure = assertThrows(Exception::class.java) { ReferenceRunner.evaluate(case) }
            assertEquals(id, expectedError, failure.javaClass.simpleName)
        } else {
            FixtureCorpus.assertOutput(id, case.get("expected"), ReferenceRunner.evaluate(case))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = FixtureCorpus.cases().map { arrayOf(it.string("id"), it) }
    }
}
