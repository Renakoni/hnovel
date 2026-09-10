package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*

/** Native JS response views backed only by bounded data, never OkHttp/Jsoup/Java wrappers. */
internal object ScriptResponses {
    fun create(context: Context, scope: Scriptable, data: JsonObject, jsoup: Boolean): ScriptableObject {
        val realm = ScriptRealm.current(context)
        val response = realm.objectIn(scope)
        val body = data.getValue("body").jsonPrimitive.content
        val url = data.getValue("url").jsonPrimitive.content
        val status = data.getValue("status").jsonPrimitive.int
        val message = data.getValue("message").jsonPrimitive.content
        val headers = data.getValue("headers").jsonObject
        // Validate every entry while still inside the guarded bridge call, before exposing
        // retained/lazy accessors. Invalid host data must not throw a JVM exception later.
        for (field in listOf("body", "url", "message")) require(data.getValue(field).jsonPrimitive.isString)
        headers.values.forEach { list -> list.jsonArray.forEach { require(it.jsonPrimitive.isString) } }
        fun values(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.jsonArray
        fun method(target: ScriptableObject, name: String, action: (Array<out Any>) -> Any?) {
            target.defineProperty(name, realm.method(scope) { cx, active, args ->
                try {
                    val limit = cx.getThreadLocal(bridgeLimitKey) as Int
                    BoundedJsonResult(limit).encode(ScriptRealm.current(cx).arrayIn(active, args))
                    action(args).also { BoundedJsonResult(limit).encode(it) }
                } catch (large: ResultTooLarge) { throw large }
                catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (_: Exception) { throw JavaScriptException(ScriptRealm.current(cx).errorIn(active, "host bridge denied"), "host-bridge", 1) }
            }, ScriptableObject.DONTENUM)
        }
        fun constant(name: String, value: Any?) = method(response, name) { require(it.isEmpty()); value }
        constant("body", body)
        constant("url", url)
        constant(if (jsoup) "statusCode" else "code", status)
        constant(if (jsoup) "statusMessage" else "message", message)
        if (!jsoup) {
            constant("getBody", body)
            constant("getUrl", url)
            constant("isSuccessful", status in 200..299)
        }
        method(response, "header") { args ->
            require(args.size == 1 && args[0] is CharSequence)
            val matching = values(args[0].toString())
            if (jsoup) matching?.joinToString(", ") { it.jsonPrimitive.content }
            else matching?.lastOrNull()?.jsonPrimitive?.content
        }
        method(response, "headers") { args ->
            require(args.isEmpty())
            realm.objectIn(scope).apply {
                if (jsoup) headers.forEach { (name, list) ->
                    list.jsonArray.firstOrNull()?.let { defineProperty(name, it.jsonPrimitive.content, ScriptableObject.EMPTY) }
                }
                method(this, "get") { names ->
                    require(names.size == 1 && names[0] is CharSequence)
                    if (jsoup) headers[names[0].toString()]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                    else values(names[0].toString())?.lastOrNull()?.jsonPrimitive?.content
                }
                method(this, "values") { names ->
                    require(names.size == 1 && names[0] is CharSequence)
                    realm.arrayIn(scope, values(names[0].toString()).orEmpty().map { it.jsonPrimitive.content }.toTypedArray())
                }
            }
        }
        if (jsoup) {
            response.defineProperty("parse", realm.method(scope) { cx, active, args ->
                try {
                    require(args.isEmpty())
                    BoundedJsonResult(cx.getThreadLocal(bridgeLimitKey) as Int).encode(body)
                    ScriptDom.parse(cx, active, body, url)
                } catch (large: ResultTooLarge) { throw large }
                catch (_: Exception) { throw JavaScriptException(ScriptRealm.current(cx).errorIn(active, "host bridge denied"), "host-bridge", 1) }
            }, ScriptableObject.DONTENUM)
        }
        return response
    }
}
