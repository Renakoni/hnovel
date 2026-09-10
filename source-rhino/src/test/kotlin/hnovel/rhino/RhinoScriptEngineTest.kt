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
}
