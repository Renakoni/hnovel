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
        fun values(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.jsonArray
        fun method(target: ScriptableObject, name: String, action: (Array<out Any>) -> Any?) {
            target.defineProperty(name, realm.method(scope) { _, _, args -> action(args) }, ScriptableObject.DONTENUM)
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
        // raw()/parse()/body streams are deliberately not Java objects; unsupported members
        // fail visibly. The view implements only the documented data access methods above.
        return response
    }
}
