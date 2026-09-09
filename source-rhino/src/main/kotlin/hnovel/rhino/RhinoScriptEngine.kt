package hnovel.rhino

import org.mozilla.javascript.*

interface HostBridge { fun call(name: String, args: List<Any?>): Any? }

private class HostBridgeObject(private val bridge: HostBridge) : ScriptableObject() {
    override fun getClassName() = "HostBridge"
    override fun get(name: String, start: Scriptable): Any = if (name == "call") object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
            if (args.isEmpty()) throw IllegalArgumentException("bridge name required")
            return bridge.call(Context.toString(args[0]), args.drop(1).map {
                if (it === Undefined.instance) null else Context.toString(it)
            })
        }
    } else super.get(name, start)
}

data class ScriptFrame(val sourceId: String, val profile: String, val bookId: String? = null, val chapterId: String? = null,
    val variables: MutableMap<String, Any?> = linkedMapOf())

data class ScriptLimits(val instructionLimit: Int = 100_000, val maxResultChars: Int = 256 * 1024,
    val maxScriptChars: Int = 256 * 1024) {
    init { require(instructionLimit > 0 && maxResultChars > 0 && maxScriptChars > 0) }
}

sealed interface ScriptResult {
    /** A bounded JSON payload; no Rhino object can escape through a successful result. */
    data class Success(val json: String) : ScriptResult {
        override fun toString() = "ScriptSuccess(chars=${json.length})"
    }
    data class Failure(val code: FailureCode, val message: String) : ScriptResult
}
enum class FailureCode { Timeout, Cancelled, Syntax, Runtime, ResultTooLarge, UnsupportedResult, BridgeDenied }

private class ScriptBudgetExceeded : Error()
private class ScriptCancelled : Error()

/** Interpreted JS is instruction-bounded. Native calls/regex still require the #86 process boundary. */
class RhinoScriptEngine(private val bridge: HostBridge, private val limits: ScriptLimits = ScriptLimits()) {
    fun evaluate(source: String, frame: ScriptFrame): ScriptResult {
        if (source.length > limits.maxScriptChars) return ScriptResult.Failure(FailureCode.ResultTooLarge, "script too large")
        // ContextFactory.call reuses an already-entered Context, whose observer we do not own.
        if (Context.getCurrentContext() != null) return ScriptResult.Failure(FailureCode.Runtime, "nested execution context")
        val factory = object : ContextFactory() {
            private var instructions = 0L
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
                instructionObserverThreshold = minOf(1000, limits.instructionLimit)
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                instructions += instructionCount
                if (instructions > limits.instructionLimit) throw ScriptBudgetExceeded()
            }
        }
        return try {
            factory.call { context ->
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                val scope = context.initSafeStandardObjects()
                scope.put("source", scope, NativeObject().apply { put("id", this, frame.sourceId); put("profile", this, frame.profile) })
                scope.put("book", scope, NativeObject().apply { frame.bookId?.let { put("id", this, it) } })
                scope.put("chapter", scope, NativeObject().apply { frame.chapterId?.let { put("id", this, it) } })
                scope.put("result", scope, Context.javaToJS(frame.variables["result"], scope))
                scope.put("host", scope, HostBridgeObject(bridge))
                val value = context.evaluateString(scope, source, "source-script", 1, null)
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                ScriptResult.Success(BoundedJsonResult(limits.maxResultChars).encode(value))
            }
        } catch (_: ScriptBudgetExceeded) { ScriptResult.Failure(FailureCode.Timeout, "instruction budget exceeded") }
          catch (_: ScriptCancelled) { ScriptResult.Failure(FailureCode.Cancelled, "script cancelled") }
          catch (_: ResultTooLarge) { ScriptResult.Failure(FailureCode.ResultTooLarge, "result too large") }
          catch (_: UnsupportedResult) { ScriptResult.Failure(FailureCode.UnsupportedResult, "result is not JSON data") }
          catch (_: WrappedException) { ScriptResult.Failure(FailureCode.BridgeDenied, "host bridge denied") }
          catch (_: EvaluatorException) { ScriptResult.Failure(FailureCode.Syntax, "syntax error") }
          catch (_: JavaScriptException) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
          catch (_: Exception) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
    }
}
