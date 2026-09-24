package hnovel.rhino

import org.mozilla.javascript.*
import java.nio.charset.Charset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Rhino's own importer, with explicit data/time operations and no reflective Java bindings. */
internal object ScriptJavaPackages {
    private class JavaString(private val text: String) : NativeObject(), CharSequence {
        override val length get() = text.length
        override fun isEmpty() = text.isEmpty()
        override fun get(index: Int) = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int) = text.subSequence(startIndex, endIndex)
        override fun getDefaultValue(hint: Class<*>?): Any = text
        override fun toString() = text
    }

    private class Parameter(val value: Any) : NativeObject()

    fun install(cx: Context, scope: ScriptableObject) {
        val realm = ScriptRealm.current(cx)
        val attributes = ScriptableObject.READONLY or ScriptableObject.PERMANENT
        val packages = mutableMapOf<String, NativeJavaPackage>()
        fun member(target: NativeJavaPackage, name: String, value: Any) {
            // NativeJavaPackage.put deliberately ignores writes; install host-owned data slots.
            target.defineOwnProperty(cx, name, realm.objectIn(scope).apply {
                put("value", this, value)
                put("writable", this, false)
                put("configurable", this, false)
            })
        }
        fun pkg(name: String): NativeJavaPackage = packages.getOrPut(name) {
            NativeJavaPackage(name, null).apply {
                parentScope = scope
                prototype = realm.objectIn(scope).prototype
                if (name.isNotEmpty()) member(pkg(name.substringBeforeLast('.', "")), name.substringAfterLast('.'), this)
            }
        }
        fun export(name: String, value: Any) = member(pkg(name.substringBeforeLast('.')), name.substringAfterLast('.'), value)
        fun method(target: ScriptableObject, name: String, action: (Context, Scriptable, Array<out Any>) -> Any?) {
            target.defineProperty(name, ScriptCalls.method(scope, "invalid Java data argument", action), attributes or ScriptableObject.DONTENUM)
        }
        fun string(context: Context, active: Scriptable, value: String): ScriptableObject = JavaString(value).apply {
            parentScope = active
            prototype = realm.stringPrototype
            method(this, "getBytes") { c, s, a ->
                require(a.size <= 1)
                bytes(c, s, value.toByteArray(charset(a.getOrNull(0))))
            }
        }
        fun parameter(active: Scriptable, value: Any) = Parameter(value).apply {
            parentScope = active
            prototype = realm.objectIn(active).prototype
        }

        export("java.lang.String", ScriptCalls.method(scope, "invalid Java string argument") { context, active, args ->
            require(args.size <= 2)
            val value = when (val input = args.firstOrNull()) {
                null -> ""
                is NativeArray -> data(input).toString(charset(args.getOrNull(1)))
                else -> { require(args.size == 1); Context.toString(input) }
            }
            string(context, active, value)
        })
        export("javax.crypto.spec.SecretKeySpec", ScriptCalls.method(scope, "invalid Java key argument") { _, active, args ->
            require(args.size == 2)
            parameter(active, SecretKeySpec(data(args[0]), Context.toString(args[1])))
        })
        export("javax.crypto.spec.IvParameterSpec", ScriptCalls.method(scope, "invalid Java IV argument") { _, active, args ->
            require(args.size == 1)
            parameter(active, IvParameterSpec(data(args[0])))
        })

        val arrays = realm.objectIn(scope)
        method(arrays, "copyOfRange") { context, active, args ->
            require(args.size == 3)
            val input = data(args[0])
            val from = integer(args[1])
            val to = integer(args[2])
            require(from in 0..input.size && to >= from)
            checkAllocation(context, to.toLong() - from)
            bytes(context, active, java.util.Arrays.copyOfRange(input, from, to))
        }
        export("java.util.Arrays", arrays)

        val base64 = realm.objectIn(scope)
        for ((name, url) in listOf("getDecoder" to false, "getUrlDecoder" to true)) method(base64, name) { _, active, args ->
            require(args.isEmpty())
            realm.objectIn(active).apply {
                method(this, "decode") { context, target, a ->
                    require(a.size == 1)
                    val decoder = if (url) Base64.getUrlDecoder() else Base64.getDecoder()
                    val decoded = if (a[0] is NativeArray) decoder.decode(data(a[0])) else decoder.decode(Context.toString(a[0]))
                    bytes(context, target, decoded)
                }
            }
        }
        for ((name, url) in listOf("getEncoder" to false, "getUrlEncoder" to true)) method(base64, name) { _, active, args ->
            require(args.isEmpty())
            realm.objectIn(active).apply {
                for (operation in listOf("encode", "encodeToString")) method(this, operation) { context, target, a ->
                    require(a.size == 1)
                    val encoder = if (url) Base64.getUrlEncoder() else Base64.getEncoder()
                    if (operation == "encodeToString") encoder.encodeToString(data(a[0]))
                    else bytes(context, target, encoder.encode(data(a[0])))
                }
            }
        }
        export("java.util.Base64", base64)

        val cipherClass = realm.objectIn(scope)
        cipherClass.defineProperty("ENCRYPT_MODE", Cipher.ENCRYPT_MODE, attributes)
        cipherClass.defineProperty("DECRYPT_MODE", Cipher.DECRYPT_MODE, attributes)
        method(cipherClass, "getInstance") { _, active, args ->
            require(args.size == 1)
            val cipher = Cipher.getInstance(Context.toString(args[0]).replace("/PKCS7Padding", "/PKCS5Padding", true))
            realm.objectIn(active).apply {
                method(this, "init") { _, _, a ->
                    require(a.size in 2..3)
                    val mode = integer(a[0])
                    require(mode == Cipher.ENCRYPT_MODE || mode == Cipher.DECRYPT_MODE)
                    val key = (a[1] as? Parameter)?.value as? SecretKeySpec ?: error("Key required")
                    if (a.size == 2) cipher.init(mode, key)
                    else cipher.init(mode, key, (a[2] as? Parameter)?.value as? IvParameterSpec ?: error("IV required"))
                    null
                }
                for (operation in listOf("update", "doFinal")) method(this, operation) { context, target, a ->
                    require(a.size == 1 || operation == "doFinal" && a.isEmpty())
                    val input = a.firstOrNull()?.let(::data) ?: byteArrayOf()
                    checkAllocation(context, cipher.getOutputSize(input.size).toLong())
                    val output = if (operation == "doFinal") cipher.doFinal(input) else cipher.update(input)
                    output?.let { bytes(context, target, it) }
                }
            }
        }
        export("javax.crypto.Cipher", cipherClass)

        val uuid = realm.objectIn(scope)
        method(uuid, "randomUUID") { _, _, args -> require(args.isEmpty()); java.util.UUID.randomUUID().toString() }
        export("java.util.UUID", uuid)
        val system = realm.objectIn(scope)
        method(system, "currentTimeMillis") { _, _, args ->
            require(args.isEmpty())
            System.currentTimeMillis()
        }
        export("java.lang.System", system)
        val thread = realm.objectIn(scope)
        method(thread, "sleep") { context, _, args ->
            require(args.size == 1 && args[0] is Number)
            val millis = (args[0] as Number).toDouble()
            require(millis.isFinite() && millis >= 0 && millis <= 9_007_199_254_740_991.0 && millis == millis.toLong().toDouble())
            // Resolve the current invocation's deadline even when a library retained this method.
            @Suppress("UNCHECKED_CAST")
            val checkDeadline = context.getThreadLocal(scriptDeadlineKey) as () -> Unit
            val started = System.nanoTime()
            while (true) {
                checkDeadline()
                val remaining = millis.toLong() - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) break
                try { Thread.sleep(minOf(remaining, 10)) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    checkDeadline()
                }
            }
            null
        }
        export("java.lang.Thread", thread)
        val hutoolBase64 = realm.objectIn(scope)
        method(hutoolBase64, "encode") { _, _, a ->
            require(a.size == 1)
            Base64.getEncoder().encodeToString(if (a[0] is NativeArray) data(a[0]) else Context.toString(a[0]).toByteArray())
        }
        for (operation in listOf("decode", "decodeStr")) method(hutoolBase64, operation) { context, active, a ->
            require(a.size == 1)
            val value = ScriptTools.decodeBase64(Context.toString(a[0]), 0, true)
            if (operation == "decodeStr") value.toString(Charsets.UTF_8) else bytes(context, active, value)
        }
        export("cn.hutool.core.codec.Base64", hutoolBase64)
        val org = ScriptableObject.getProperty(scope, "org") as Scriptable
        val jsoup = ScriptableObject.getProperty(org, "jsoup") as Scriptable
        export("org.jsoup.Jsoup", ScriptableObject.getProperty(jsoup, "Jsoup"))

        // Reuse Rhino's package lookup, with/closure scope and ambiguous-import handling.
        // The engine's deny-all ClassShutter remains in force for every unlisted Java class.
        scope.defineProperty("Packages", pkg(""), attributes)
        ImporterTopLevel.init(cx, scope, false)
    }

    private fun charset(value: Any?): Charset = value?.let { Charset.forName(Context.toString(it)) } ?: Charsets.UTF_8

    private fun integer(value: Any): Int {
        val number = Context.toNumber(value)
        require(number.isFinite() && number == number.toInt().toDouble())
        return number.toInt()
    }

    private fun data(value: Any): ByteArray {
        require(value is NativeArray)
        checkAllocation(Context.getCurrentContext(), value.length)
        return ByteArray(value.length.toInt()) { integer(value.get(it, value)).also { n -> require(n in -128..255) }.toByte() }
    }

    private fun checkAllocation(cx: Context, length: Long) {
        if (length > (cx.getThreadLocal(bridgeLimitKey) as Int)) throw ResultTooLarge()
    }

    private fun bytes(cx: Context, scope: Scriptable, bytes: ByteArray): NativeArray {
        checkAllocation(cx, bytes.size.toLong())
        return ScriptRealm.current(cx).arrayIn(scope, bytes.map { it.toInt() }.toTypedArray())
    }
}
