package hnovel.rhino

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/** The Jsoup text parser is worker-owned; this namespace does not expose Java classes or IO. */
internal object ScriptParsers {
    fun install(cx: Context, scope: ScriptableObject) {
        val realm = ScriptRealm.current(cx)
        val attributes = ScriptableObject.READONLY or ScriptableObject.PERMANENT
        val parser = realm.objectIn(scope)
        parser.defineProperty("parse", ScriptCalls.method(scope, "invalid parser argument") { context, active, args ->
            require(args.size in 1..2 && args[0] is CharSequence)
            require(args.size == 1 || args[1] is CharSequence)
            ScriptDom.parse(context, active, args[0].toString(), args.getOrNull(1)?.toString().orEmpty())
        }, attributes)
        val jsoup = realm.objectIn(scope)
        jsoup.defineProperty("Jsoup", parser, attributes)
        val org = realm.objectIn(scope)
        org.defineProperty("jsoup", jsoup, attributes)
        scope.defineProperty("org", org, attributes)
    }
}
