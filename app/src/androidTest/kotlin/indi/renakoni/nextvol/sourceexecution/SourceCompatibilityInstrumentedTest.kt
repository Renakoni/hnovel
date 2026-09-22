package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.*
import hnovel.imports.LEGADO_PROFILE
import hnovel.rules.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class SourceCompatibilityInstrumentedTest {
    @Test fun packagedSelectorsKeepJavaRegexSemanticsWhenRe2jIsPresent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = ExecutionAuthority()
        val identity = authority.issue("selector-regex", LEGADO_PROFILE, "1")
        val executor = AndroidIsolatedExecutor(context, authority)
        val html = RuleValue.Text("<article><p style='color:white'>正文正文</p>" +
            "<p style='color:red'>广告</p></article>")
        try {
            // The app also uses RE2J for TXT parsing. It must not change source selectors:
            // both the ordinary rule path and script DOM support lookarounds/backreferences.
            for (rule in listOf("p:matchesOwn((正文)\\1)@text", """
                @js:var doc = org.jsoup.Jsoup.parse(result);
                doc.select('[style~=(?i)color:(?!white)]').remove();
                doc.select('p:matchesOwn((正文)\\1)').text();
            """.trimIndent())) {
                val result = executor.execute(identity, ExecutionTask.Rule(rule, html, OutputKind.Text))
                assertTrue(result.toString(), result is ExecutionResult.Success)
                val value = kotlinx.serialization.json.Json.decodeFromString<ExecutedRule>(
                    (result as ExecutionResult.Success).output).value
                assertEquals(RuleValue.Text("正文正文"), value)
            }
        } finally { executor.close() }
    }

    @Test fun binderPreservesTheMissingLibraryBindingAndTheNextInvocationRecovers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = ExecutionAuthority()
        val identity = authority.issue("fixture", LEGADO_PROFILE, "1")
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            val failure = executor.execute(identity, ExecutionTask.Rule("@js:42", RuleValue.Empty,
                location = RuleLocation("searchUrl"), libraryCode = "new JavaAdapter()")) as ExecutionResult.Failure
            assertEquals(FailureCode.UnsupportedDependency, failure.code)
            assertEquals(ScriptDependency.JavaAdapter, failure.dependency)
            assertEquals(RuleLocation("jsLib"), failure.ruleError!!.location)
            assertEquals(ExecutionResult.Success("\"fixture-prelude\""), executor.execute(identity,
                ExecutionTask.Script("source.loginUrl", sourceLoginUrl = "fixture-prelude")))
        } finally { executor.close() }
    }

    @Test fun nativeImporterDecryptsThroughBinderWithoutExposingJavaIoOrReflection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "目录与正文保持原规则"
        val key = "0123456789abcdef".toByteArray()
        val iv = "fedcba9876543210".toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val encoded = Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val identity = authority.issue("importer", LEGADO_PROFILE, "1")
        try {
            val script = """
                var imported = new JavaImporter();
                imported.importPackage(Packages.java.lang, Packages.java.util,
                    Packages.javax.crypto, Packages.javax.crypto.spec);
                with (imported) {
                    var data = Base64.getDecoder().decode('$encoded');
                    var cipher = Cipher.getInstance('AES/CBC/PKCS5Padding');
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(String('0123456789abcdef').getBytes(), 'AES'),
                        IvParameterSpec(Arrays.copyOfRange(data, 0, 16)));
                    if (typeof cipher.getClass !== 'undefined' || typeof cipher.getProvider !== 'undefined')
                        throw new Error('Java object escaped');
                    String(cipher.doFinal(Arrays.copyOfRange(data, 16, data.length)));
                }
            """.trimIndent()
            assertEquals(ExecutionResult.Success(JsonPrimitive(text).toString()),
                executor.execute(identity, ExecutionTask.Script(script)))
            for (code in listOf("Packages.java.lang.System.exit(0)",
                "new Packages.java.io.File('/unused')", "Packages.java.lang.Class.forName('java.lang.Runtime')")) {
                val failure = executor.execute(identity, ExecutionTask.Script(code))
                assertTrue(failure.toString(), failure is ExecutionResult.Failure)
                assertEquals(FailureCode.ScriptRuntime, (failure as ExecutionResult.Failure).code)
                assertEquals(ExecutionResult.Success("42"), executor.execute(identity, ExecutionTask.Script("21*2")))
            }
        } finally { executor.close() }
    }
}
