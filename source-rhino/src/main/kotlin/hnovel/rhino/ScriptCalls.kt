package hnovel.rhino

import org.mozilla.javascript.*

/** Shared guard for native data methods, including calls retained by a library. */
internal object ScriptCalls {
    fun method(scope: Scriptable, error: String, action: (Context, Scriptable, Array<out Any>) -> Any?): BaseFunction =
        ScriptRealm.current(Context.getCurrentContext()).method(scope) { cx, active, args ->
            try {
                val limit = cx.getThreadLocal(bridgeLimitKey) as Int
                BoundedJsonResult(limit).encode(ScriptRealm.current(cx).arrayIn(active, args))
                val result = action(cx, active, args)
                BoundedJsonResult(limit).encode(result)
                result
            } catch (large: ResultTooLarge) { throw large }
            catch (unsupported: UnsupportedResult) { throw unsupported }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (error: JavaScriptException) { throw error }
            catch (_: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                throw JavaScriptException(ScriptRealm.current(cx).errorIn(active, error), "script-data", 1)
            }
        }
}
