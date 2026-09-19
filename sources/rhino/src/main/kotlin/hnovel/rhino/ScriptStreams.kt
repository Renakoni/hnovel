package hnovel.rhino

import org.mozilla.javascript.*
import java.io.InputStream
import java.io.Reader

/** Streams are bounded in-memory response data; no host descriptor or socket is reachable. */
internal object ScriptStreams {
    fun wrap(cx: Context, scope: Scriptable, input: InputStream): ScriptableObject = stream(cx, scope, input, null)
    fun wrap(cx: Context, scope: Scriptable, reader: Reader): ScriptableObject = stream(cx, scope, null, reader)
    private fun stream(cx: Context, scope: Scriptable, input: InputStream?, reader: Reader?): ScriptableObject {
        val realm = ScriptRealm.current(cx)
        val target = ScriptDomValue(input ?: reader!!).apply { parentScope = scope; prototype = realm.objectIn(scope).prototype }
        fun method(name: String, action: (Array<out Any>) -> Any?) {
            target.defineProperty(name, ScriptCalls.method(scope, "invalid stream operation") { _, _, args -> action(args) }, ScriptableObject.DONTENUM)
        }
        var closed = false
        fun open() = check(!closed)
        method("close") { a -> require(a.isEmpty()); input?.close(); reader?.close(); closed = true; null }
        method("read") { a ->
            open()
            if (a.isEmpty()) input?.read() ?: reader!!.read()
            else {
                require(a.size == 1 || a.size == 3)
                val array = a[0] as? NativeArray ?: error("Array required")
                val offset = if (a.size == 3) Context.toNumber(a[1]).toInt() else 0
                val length = if (a.size == 3) Context.toNumber(a[2]).toInt() else array.length.toInt()
                require(offset >= 0 && length >= 0 && offset.toLong() + length <= array.length)
                if (input != null) {
                    val bytes = ByteArray(length); val count = input.read(bytes)
                    for (i in 0 until maxOf(0, count)) array.put(offset + i, array, bytes[i].toInt())
                    count
                } else {
                    val chars = CharArray(length); val count = reader!!.read(chars)
                    for (i in 0 until maxOf(0, count)) array.put(offset + i, array, chars[i].toString())
                    count
                }
            }
        }
        method("skip") { a -> open(); require(a.size == 1); val n = Context.toNumber(a[0]).toLong(); input?.skip(n) ?: reader!!.skip(n) }
        method("markSupported") { a -> require(a.isEmpty()); input?.markSupported() ?: reader!!.markSupported() }
        method("mark") { a -> open(); require(a.size == 1); val n = Context.toNumber(a[0]).toInt(); if (input != null) input.mark(n) else reader!!.mark(n); null }
        method("reset") { a -> open(); require(a.isEmpty()); if (input != null) input.reset() else reader!!.reset(); null }
        method(if (input != null) "available" else "ready") { a -> open(); require(a.isEmpty()); if (input != null) input.available() else reader!!.ready() }
        return target
    }
}
