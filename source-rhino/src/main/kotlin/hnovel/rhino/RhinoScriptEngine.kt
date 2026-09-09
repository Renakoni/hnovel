package hnovel.rhino

import org.mozilla.javascript.*

interface HostBridge { fun call(name: String, args: List<Any?>): Any? }

private class HostBridgeObject(private val bridge: HostBridge) : ScriptableObject() {
 override fun getClassName() = "HostBridge"
 override fun get(name: String, start: Scriptable): Any = if (name == "call") object : BaseFunction() {
  override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
   if (args.isEmpty()) throw IllegalArgumentException("bridge name required")
   return bridge.call(Context.toString(args[0]), args.drop(1).map { if (it === Undefined.instance) null else Context.toString(it) })
  }
 } else super.get(name, start)
}
data class ScriptFrame(val sourceId: String, val profile: String, val bookId: String? = null, val chapterId: String? = null,
    val variables: MutableMap<String, Any?> = linkedMapOf())
data class ScriptLimits(val instructionLimit: Int = 100_000, val maxResultChars: Int = 256 * 1024)
sealed interface ScriptResult { data class Success(val value: Any?): ScriptResult; data class Failure(val code: FailureCode, val message: String): ScriptResult }
enum class FailureCode { Timeout, Syntax, Runtime, ResultTooLarge, BridgeDenied }

/** Rhino execution with an allow-listed host bridge and a fresh scope per invocation. */
class RhinoScriptEngine(private val bridge: HostBridge, private val limits: ScriptLimits = ScriptLimits()) {
 fun evaluate(source: String, frame: ScriptFrame): ScriptResult {
  if (source.length > limits.maxResultChars) return ScriptResult.Failure(FailureCode.ResultTooLarge,"script too large")
  val context = Context.enter()
  return try {
   context.instructionObserverThreshold = limits.instructionLimit
   context.setClassShutter { false }
   val scope = context.initSafeStandardObjects()
   scope.put("source", scope, NativeObject().apply { put("id",this,frame.sourceId); put("profile",this,frame.profile) })
   scope.put("book", scope, NativeObject().apply { frame.bookId?.let { put("id",this,it) } })
   scope.put("chapter", scope, NativeObject().apply { frame.chapterId?.let { put("id",this,it) } })
   scope.put("result", scope, Context.javaToJS(frame.variables["result"], scope))
   scope.put("host", scope, HostBridgeObject(bridge))
   val value = context.evaluateString(scope, source, "source-script", 1, null)
   val unwrapped = Context.jsToJava(value, Any::class.java)
   if (unwrapped.toString().length > limits.maxResultChars) ScriptResult.Failure(FailureCode.ResultTooLarge,"result too large") else ScriptResult.Success(unwrapped)
  } catch (_: EvaluatorException) { ScriptResult.Failure(FailureCode.Syntax,"syntax error") }
    catch (_: JavaScriptException) { ScriptResult.Failure(FailureCode.Runtime,"script failed") }
    catch (_: WrappedException) { ScriptResult.Failure(FailureCode.BridgeDenied,"host bridge denied") }
    catch (_: Exception) { ScriptResult.Failure(FailureCode.Runtime,"script failed") }
  finally { Context.exit() }
 }
}


