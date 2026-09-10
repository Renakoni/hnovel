package hnovel.rhino
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class RhinoScriptEngineTest {
 private val engine=RhinoScriptEngine(HostBridge { name,args -> if(name=="upper") JsonPrimitive(args.single().jsonPrimitive.content.uppercase()) else error("denied") })
 private val frame=ScriptFrame("source-a","legado",variables=mapOf("result" to JsonPrimitive("ok")))
 @Test fun freshScopedContextAndBridge() { val r=engine.evaluate("host.call('upper', result) + ':' + source.id",frame) as ScriptResult.Success; assertEquals("\"OK:source-a\"",r.json) }
 @Test fun syntaxAndBridgeErrorsAreStructured() { assertTrue(engine.evaluate("return ;",frame) is ScriptResult.Failure); assertTrue(engine.evaluate("host.call('bad','x')",frame) is ScriptResult.Failure) }
 @Test fun classesAreNotExposed() { val r=engine.evaluate("Packages.java.lang.System.exit",frame); assertTrue(r is ScriptResult.Failure) }

 @Test fun bridgeFunctionsSupportCallApplyAndBindWithAndWithoutALibrary() {
  val calls = mutableListOf<String>()
  val bridgeEngine = RhinoScriptEngine(HostBridge { name, args ->
   calls += name
   JsonPrimitive(args.single().jsonPrimitive.content.uppercase())
  })
  val script = "[java.ajax.call(null,'a'),cache.get.apply(cache,['b']),source.get.bind(source)('c')," +
   "host.call.call(null,'upper','d'),source.getKey.apply(null,[]),java.md5Encode.bind(java)('')," +
   "java.ajax instanceof Function]"
  ScriptLibrary("source-a", "legado", "var holder={};").use { library ->
    for (scope in listOf(null, library)) {
     assertEquals(ScriptResult.Success("[\"A\",\"B\",\"C\",\"D\",\"source-a\",\"d41d8cd98f00b204e9800998ecf8427e\",true]"),
      bridgeEngine.evaluate(script, frame, scope))
   }
   assertEquals(ScriptResult.Success("1"), bridgeEngine.evaluate("holder.hash=java.md5Encode.bind(java);1", frame, library))
   assertEquals(ScriptResult.Success("\"d41d8cd98f00b204e9800998ecf8427e\""), bridgeEngine.evaluate("holder.hash('')", frame, library))
  }
  assertEquals(List(2) { listOf("java.ajax", "cache.get", "source.get", "upper") }.flatten(), calls)
 }

 @Test fun globalCompletionAndTopLevelReturnFollowThePinnedEngineEntryPoint() {
  val scripts = listOf("21*2", "var value=21; value*2", "function answer(){return 42;} answer()")
  val cx = org.mozilla.javascript.Context.enter()
  val expected = try {
   cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
   scripts.map { script ->
    val scope = cx.initSafeStandardObjects()
    org.mozilla.javascript.Context.toString(cx.evaluateString(scope, script, "pinned-global-entry", 1, null))
   }.also {
    assertThrows(org.mozilla.javascript.EvaluatorException::class.java) {
     cx.evaluateString(cx.initSafeStandardObjects(), "return 42", "pinned-global-entry", 1, null)
    }
   }
  } finally { org.mozilla.javascript.Context.exit() }
  assertEquals(expected, scripts.map { (engine.evaluate(it,frame) as ScriptResult.Success).json })
  assertEquals(FailureCode.Syntax, (engine.evaluate("return 42",frame) as ScriptResult.Failure).code)
 }
}
