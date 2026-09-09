package hnovel.rhino
import org.junit.Assert.*
import org.junit.Test
class RhinoScriptEngineTest {
 private val engine=RhinoScriptEngine(object:HostBridge { override fun call(name:String,args:List<Any?>)=if(name=="upper") args.single().toString().uppercase() else error("denied") })
 private val frame=ScriptFrame("source-a","legado",variables=linkedMapOf("result" to "ok"))
 @Test fun freshScopedContextAndBridge() { val r=engine.evaluate("host.call('upper', result) + ':' + source.id",frame) as ScriptResult.Success; assertEquals("\"OK:source-a\"",r.json) }
 @Test fun syntaxAndBridgeErrorsAreStructured() { assertTrue(engine.evaluate("return ;",frame) is ScriptResult.Failure); assertTrue(engine.evaluate("host.call('bad','x')",frame) is ScriptResult.Failure) }
 @Test fun classesAreNotExposed() { val r=engine.evaluate("Packages.java.lang.System.exit",frame); assertTrue(r is ScriptResult.Failure) }
}
