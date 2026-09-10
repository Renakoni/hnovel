package hnovel.rhino

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.mozilla.javascript.*
import kotlinx.serialization.json.*

/** A worker-local DOM handle, serialized as markup at every data boundary. */
internal class ScriptDomElement(val element: Element) : NativeObject()

internal object ScriptDom {
    fun parse(cx: Context, scope: Scriptable, html: String, baseUrl: String, xml: Boolean = false): ScriptableObject =
        node(cx, scope, Jsoup.parse(html, baseUrl, if (xml) Parser.xmlParser() else Parser.htmlParser()))

    fun fragment(cx: Context, scope: Scriptable, html: String, baseUrl: String, xml: Boolean): ScriptableObject {
        val doc = Jsoup.parse(html, baseUrl, if (xml) Parser.xmlParser() else Parser.htmlParser())
        return node(cx, scope, (if (xml) doc.children().firstOrNull() else doc.body().children().firstOrNull()) ?: doc)
    }

    private fun method(target: ScriptableObject, scope: Scriptable, name: String, action: (Context, Scriptable, List<JsonElement>) -> Any?) {
        target.defineProperty(name, ScriptRealm.current(Context.getCurrentContext()).method(scope) { cx, active, raw ->
            try {
                val limit = cx.getThreadLocal(bridgeLimitKey) as Int
                val args = Json.parseToJsonElement(BoundedJsonResult(limit).encode(ScriptRealm.current(cx).arrayIn(active, raw))).jsonArray
                val result = action(cx, active, args)
                BoundedJsonResult(limit).encode(result)
                result
            } catch (large: ResultTooLarge) { throw large }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { throw JavaScriptException(ScriptRealm.current(cx).errorIn(active, "invalid DOM argument"), "script-dom", 1) }
        }, ScriptableObject.DONTENUM)
    }

    fun node(cx: Context, scope: Scriptable, element: Element): ScriptableObject {
        val realm = ScriptRealm.current(cx)
        val result = ScriptDomElement(element).apply { parentScope = scope; prototype = realm.objectIn(scope).prototype }
        fun member(name: String, action: (Context, Scriptable, List<JsonElement>) -> Any?) = method(result, scope, name, action)
        for (name in listOf("text", "html", "outerHtml", "ownText", "tagName", "id", "className", "val", "data", "baseUri")) {
            member(name) { _, _, a ->
                require(a.isEmpty())
                when (name) {
                    "text" -> element.text(); "html" -> element.html(); "outerHtml" -> element.outerHtml()
                    "ownText" -> element.ownText(); "tagName" -> element.tagName(); "id" -> element.id()
                    "className" -> element.className(); "val" -> element.`val`(); "data" -> element.data()
                    else -> element.baseUri()
                }
            }
        }
        member("toString") { _, _, a -> require(a.isEmpty()); element.outerHtml() }
        for (name in listOf("attr", "hasAttr", "hasClass", "absUrl", "is")) member(name) { _, _, a ->
            require(a.size == 1 && a[0].jsonPrimitive.isString)
            val text = a[0].jsonPrimitive.content
            when (name) {
                "attr" -> element.attr(text); "hasAttr" -> element.hasAttr(text); "hasClass" -> element.hasClass(text)
                "absUrl" -> element.absUrl(text); else -> element.`is`(text)
            }
        }
        member("select") { context, active, a -> require(a.size == 1); elements(context, active, element.select(a[0].jsonPrimitive.content)) }
        member("selectFirst") { context, active, a -> require(a.size == 1); element.selectFirst(a[0].jsonPrimitive.content)?.let { node(context, active, it) } }
        for (name in listOf("parent", "nextElementSibling", "previousElementSibling", "clone")) member(name) { context, active, a ->
            require(a.isEmpty())
            val selected = when (name) { "parent" -> element.parent(); "nextElementSibling" -> element.nextElementSibling(); "previousElementSibling" -> element.previousElementSibling(); else -> element.clone() }
            selected?.let { node(context, active, it) }
        }
        member("children") { context, active, a -> require(a.isEmpty()); elements(context, active, element.children()) }
        member("child") { context, active, a -> require(a.size == 1); node(context, active, element.child(a[0].jsonPrimitive.int)) }
        member("remove") { _, _, a -> require(a.isEmpty()); element.remove(); result }
        return result
    }

    fun elements(cx: Context, scope: Scriptable, nodes: List<Element>): NativeArray {
        val realm = ScriptRealm.current(cx)
        val result = realm.arrayIn(scope, nodes.map { node(cx, scope, it) }.toTypedArray())
        val collection = org.jsoup.select.Elements(nodes)
        for (name in listOf("size", "isEmpty", "text", "html", "outerHtml", "toString")) method(result, scope, name) { _, _, a ->
            require(a.isEmpty())
            when (name) { "size" -> nodes.size; "isEmpty" -> nodes.isEmpty(); "text" -> collection.text(); "html" -> collection.html(); else -> collection.outerHtml() }
        }
        method(result, scope, "get") { context, active, a -> require(a.size == 1); node(context, active, nodes[a[0].jsonPrimitive.int]) }
        method(result, scope, "first") { context, active, a -> require(a.isEmpty()); nodes.firstOrNull()?.let { node(context, active, it) } }
        method(result, scope, "last") { context, active, a -> require(a.isEmpty()); nodes.lastOrNull()?.let { node(context, active, it) } }
        method(result, scope, "select") { context, active, a -> require(a.size == 1); elements(context, active, collection.select(a[0].jsonPrimitive.content)) }
        method(result, scope, "attr") { _, _, a -> require(a.size == 1); collection.attr(a[0].jsonPrimitive.content) }
        return result
    }
}
