package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.net.URL
import java.net.URLDecoder

/** Pinned JsURL is a parsed data object, not a connection factory. */
internal object ScriptUrls {
    fun create(cx: Context, scope: Scriptable, args: List<JsonElement>): ScriptableObject {
        require(args.size in 1..2)
        val input = args[0].jsonPrimitive.also { require(it.isString) }.content
        val base = args.getOrNull(1)?.takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
        val url = if (base.isNullOrEmpty()) URL(input) else URL(URL(base), input)
        val params = url.query?.split('&')?.mapNotNull {
            val pair = it.split('=',limit=2)
            if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1],"UTF-8") else null
        }?.toMap()?.toMutableMap()
        val values = mapOf("host" to url.host, "origin" to "${url.protocol}://${url.host}${if (url.port > 0) ":${url.port}" else ""}",
            "pathname" to url.path, "searchParams" to params?.let { ScriptData.map(cx,scope,it) })
        return ScriptRealm.current(cx).objectIn(scope).apply {
            values.forEach { (key,value) ->
                defineProperty(key,value,ScriptableObject.READONLY)
                defineProperty("get"+key.replaceFirstChar { it.uppercaseChar() },ScriptCalls.method(scope,"invalid URL argument") { _,_,a -> require(a.isEmpty());value },ScriptableObject.DONTENUM)
            }
        }.also { BoundedJsonResult(cx.getThreadLocal(bridgeLimitKey) as Int).encode(it) }
    }
}
