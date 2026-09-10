package hnovel.rhino

import org.mozilla.javascript.*

/** Capture intrinsic prototypes before library code can shadow the global constructors. */
internal class ScriptRealm(context: Context) {
    val global: ScriptableObject = context.initSafeStandardObjects()
    private val objectPrototype = ScriptableObject.getObjectPrototype(global)
    private val arrayPrototype = ScriptableObject.getArrayPrototype(global)
    val functionPrototype: Scriptable = ScriptableObject.getFunctionPrototype(global)
    private val errorPrototype = ScriptableObject.getClassPrototype(global, "Error")

    fun objectIn(scope: Scriptable) = NativeObject().apply { parentScope = scope; prototype = objectPrototype }
    fun arrayIn(scope: Scriptable, values: Array<out Any?>) = NativeArray(values).apply { parentScope = scope; prototype = arrayPrototype }
    fun errorIn(scope: Scriptable, message: String) = NativeObject().apply {
        parentScope = scope
        prototype = errorPrototype
        defineProperty("message", message, ScriptableObject.DONTENUM)
    }

    fun method(scope: Scriptable, action: (Context, Scriptable, Array<out Any>) -> Any?) =
        object : BaseFunction(scope, functionPrototype) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any>): Any? = action(cx, scope, args)
        }

    companion object {
        private val key = Any()
        fun install(context: Context, realm: ScriptRealm) = context.putThreadLocal(key, realm)
        fun current(context: Context) = context.getThreadLocal(key) as ScriptRealm
    }
}
