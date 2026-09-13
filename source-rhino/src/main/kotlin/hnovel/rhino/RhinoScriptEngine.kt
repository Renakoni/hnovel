package hnovel.rhino

import org.mozilla.javascript.*
import kotlinx.serialization.json.*
import hnovel.rules.*

/** Synchronous data-only port. The host binds authority; scripts cannot supply a source ticket. */
fun interface HostBridge { fun call(name: String, args: List<JsonElement>): JsonElement }

private class BridgeRejected(value: Any) : JavaScriptException(value, "host-bridge", 1)
private class RequestRejected(value: Any) : JavaScriptException(value, "request-options", 1)

internal val bridgeLimitKey = Any()
internal val scriptLibraryKey = Any()

private class ScriptBridge(private val bridge: HostBridge, private val rules: ScriptRuleHelpers, private val requests: ScriptRequestTemplates, archives: ArchiveDecoder) {
    private val resources = ScriptResources(bridge, requests, archives)
    private val fonts = ScriptFonts(resources::download)
    fun call(cx: Context, scope: Scriptable, name: String, args: Array<out Any>): Any? {
            val maxChars = cx.getThreadLocal(bridgeLimitKey) as Int
            if (name.length > 256) throw ResultTooLarge()
            val realm = ScriptRealm.current(cx)
            val pureTool = name.startsWith("java.") && name.removePrefix("java.") in ScriptTools.methods
            return try {
                if (name.startsWith("java.") && name.substringAfter("java.") in fonts.methods)
                    return fonts.call(cx, scope, name.substringAfter("java."), args)
                val data = BoundedJsonResult(maxChars).encode(realm.arrayIn(scope, args.copyOf()))
                val arguments = Json.parseToJsonElement(data).jsonArray
                if (name == "response.view") {
                    require(arguments.size == 1)
                    ScriptResponses.validate(arguments.single(), maxChars)
                    return ScriptResponses.create(cx, scope, arguments.single().jsonObject, false)
                }
                if (name == "java.toURL") return ScriptUrls.create(cx, scope, arguments)
                if (name.removePrefix("java.") in ScriptCryptoObjects.factories && name.startsWith("java."))
                    return ScriptCryptoObjects.create(cx, scope, name.removePrefix("java."), arguments)
                val result = if (name == "request.prepare") JsonArray(requests.prepare(cx, "java.connect", arguments))
                else if (name == "java.readTxtFile") resources.text(cx, arguments)
                else if (name.startsWith("java.") && name.substringAfter("java.") in resources.methods) resources.archive(cx, name.substringAfter("java."), arguments)
                else if (rules.supports(name, arguments)) rules.call(cx, name, arguments)
                else if (pureTool) ScriptTools.call(name.removePrefix("java."), arguments) else requests.call(cx, bridge, name, arguments)
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                val networkResponse = name in setOf("java.ajaxAll", "java.connect", "java.startBrowserAwait") ||
                    name in setOf("java.get", "java.head", "java.post") && args.size >= 2
                if (networkResponse) ScriptResponses.validate(result, maxChars)
                val converted = if (networkResponse) null else JsonScriptData(cx, scope, maxChars).convert(result)
                when {
                    name in setOf("source.getLoginInfoMap", "source.getLoginHeaderMap") && result != JsonNull ->
                        ScriptData.map(cx, scope, result.jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap())
                    name == "java.getElement" || name == "java.getElements" -> rules.elementView(cx, scope, result)
                    name == "java.ajaxAll" -> realm.arrayIn(scope, result.jsonArray.map { ScriptResponses.create(cx, scope, it.jsonObject, false) }.toTypedArray())
                    name == "java.startBrowserAwait" -> ScriptResponses.create(cx, scope, result.jsonObject, true)
                    name == "java.connect" -> ScriptResponses.create(cx, scope, result.jsonObject, false)
                    name in setOf("java.get", "java.head", "java.post") && args.size >= 2 -> ScriptResponses.create(cx, scope, result.jsonObject, true)
                    else -> converted
                }
            }
                catch (_: ArchiveSizeLimitExceeded) { throw ResultTooLarge() }
                catch (large: ResultTooLarge) { throw large }
                catch (unsupported: UnsupportedResult) { throw unsupported }
                catch (cancelled: java.util.concurrent.CancellationException) { throw ScriptCancelled() }
                catch (_: RequestOptionsException) {
                    if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                    throw RequestRejected(realm.errorIn(scope, "invalid request options"))
                }
                catch (rejected: RequestRejected) { throw rejected }
                // request.prepare can evaluate nested source code. Preserve its script/bridge
                // exception instead of relabelling a caught network error followed by a source error.
                catch (error: JavaScriptException) { throw error }
                catch (_: Exception) {
                    if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                    if (pureTool || name.removePrefix("java.") in ScriptCryptoObjects.factories) throw JavaScriptException(realm.errorIn(scope, "invalid tool argument"), "script-tool", 1)
                    throw BridgeRejected(realm.errorIn(scope, "host bridge denied"))
                }
    }

    fun install(context: Context, scope: Scriptable, frame: ScriptFrame) {
        val realm = ScriptRealm.current(context)
        fun method(target: ScriptableObject, name: String, action: (Context, Scriptable, Array<out Any>) -> Any?) {
            target.defineProperty(name, realm.method(scope, action), ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        }
        fun objectFor(name: String, methods: List<String>): ScriptableObject {
            val target = realm.objectIn(scope)
            methods.forEach { member -> method(target, member) { cx, activeScope, args -> call(cx, activeScope, "$name.$member", args) } }
            scope.put(name, scope, target)
            return target
        }
        val host = objectFor("host", emptyList())
        method(host, "call") { cx, activeScope, args ->
            require(args.isNotEmpty() && args[0] is CharSequence) { "bridge name required" }
            call(cx, activeScope, args[0].toString(), args.drop(1).toTypedArray())
        }
        val javaBridge = objectFor("java", listOf("ajax", "ajaxAll", "connect", "get", "head", "post", "getCookie", "androidId",
            "put", "getString", "getStringList", "getElement", "getElements", "importScript", "cacheFile", "downloadFile",
            "readFile", "readTxtFile", "deleteFile", "toURL", "webView", "webViewGetSource", "webViewGetOverrideUrl",
            "startBrowser", "startBrowserAwait", "getVerificationCode") + ScriptTools.methods + ScriptCryptoObjects.factories + fonts.methods + resources.methods)
        method(javaBridge, "setContent") { cx, activeScope, args ->
            call(cx, activeScope, "java.setContent", args)
            javaBridge
        }
        objectFor("cache", listOf("get", "put", "delete"))
        objectFor("cookie", listOf("getCookie", "getKey", "setCookie", "replaceCookie", "removeCookie"))
        val source = objectFor("source", listOf("get", "put", "getVariable", "setVariable", "getKey", "getLoginInfo", "getLoginInfoMap",
            "putLoginInfo", "removeLoginInfo", "getLoginHeader", "getLoginHeaderMap", "putLoginHeader", "removeLoginHeader"))
        source.defineProperty("id", frame.sourceId, ScriptableObject.READONLY)
        source.defineProperty("profile", frame.profile, ScriptableObject.READONLY)
        method(javaBridge, "getSource") { _, _, args -> require(args.isEmpty()); source }
        frame.discovery?.install(context, scope, javaBridge, source)
        if (frame.discovery != null) method(javaBridge, "removeCookie") { cx, active, args ->
            call(cx, active, "cookie.removeCookie", args)
        }
    }
}

data class ScriptFrame(val sourceId: String, val profile: String, val bookId: String? = null, val chapterId: String? = null,
    val variables: Map<String, JsonElement> = emptyMap(), val key: String = "", val page: Int = 1,
    val baseUrl: String = "", val ruleContext: RuleContext? = null, val ruleInput: RuleValue? = null,
    val ruleBudget: RuleBudget? = null, val book: JsonObject = JsonObject(emptyMap()),
    val chapter: JsonObject = JsonObject(emptyMap()), val chineseConverter: Int = 0, val sourceHeaderRule: String = "",
    val discovery: ScriptDiscovery? = null)

data class ScriptLimits(val instructionLimit: Int = 100_000, val maxResultChars: Int = 256 * 1024,
    val maxScriptChars: Int = 256 * 1024, val maxBridgeChars: Int = DEFAULT_BRIDGE_CHARS,
    val maxInterpreterStackDepth: Int = 1000) {
    init { require(instructionLimit > 0 && maxResultChars > 0 && maxScriptChars > 0 && maxBridgeChars > 0 && maxInterpreterStackDepth in 1..1000) }
    companion object { const val DEFAULT_BRIDGE_CHARS = 64 * 1024 }
}

sealed interface ScriptResult {
    /** A bounded JSON payload; no Rhino object can escape through a successful result. */
    data class Success(val json: String) : ScriptResult {
        override fun toString() = "ScriptSuccess(chars=${json.length})"
    }
    data class Failure(val code: FailureCode, val message: String) : ScriptResult
}
enum class FailureCode { Timeout, Cancelled, Syntax, Runtime, ResultTooLarge, UnsupportedResult, BridgeDenied, RequestSyntax }

internal class ScriptBudgetExceeded : Error()
private class ScriptCancelled : Error()
private class ScriptSyntaxError : Error()

internal fun evaluateGlobal(context: Context, scope: Scriptable, code: String, name: String): Any? {
    val compiled = try { context.compileString(code, name, 1, null) }
        catch (_: EvaluatorException) { throw ScriptSyntaxError() }
    return compiled.exec(context, scope)
}

/** Interpreted JS is instruction-bounded. Native calls/regex still require the #86 process boundary. */
class RhinoScriptEngine(private val bridge: HostBridge, private val limits: ScriptLimits = ScriptLimits(), private val archives: ArchiveDecoder = ArchiveDecoder.Zip) {
    fun evaluate(source: String, frame: ScriptFrame, library: ScriptLibrary? = null): ScriptResult =
        if (library == null) evaluateOwned(source, frame, null)
        else synchronized(library) { evaluateOwned(source, frame, library) }

    private fun evaluateOwned(source: String, frame: ScriptFrame, library: ScriptLibrary?): ScriptResult {
        if (library != null && (library.closed || library.sourceId != frame.sourceId || library.profile != frame.profile))
            return ScriptResult.Failure(FailureCode.BridgeDenied, "invalid library owner")
        if (library != null && library.scripts.sumOf { it.length.toLong() + 1 } > limits.maxScriptChars)
            return ScriptResult.Failure(FailureCode.ResultTooLarge, "library too large")
        if (source.length > limits.maxScriptChars) return ScriptResult.Failure(FailureCode.ResultTooLarge, "script too large")
        // ContextFactory.call reuses an already-entered Context, whose observer we do not own.
        if (Context.getCurrentContext() != null) return ScriptResult.Failure(FailureCode.Runtime, "nested execution context")
        val factory = object : ContextFactory() {
            private var instructions = 0L
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
                maximumInterpreterStackDepth = limits.maxInterpreterStackDepth
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
                val realm = library?.realm ?: ScriptRealm(context)
                ScriptRealm.install(context, realm)
                context.putThreadLocal(bridgeLimitKey, limits.maxBridgeChars)
                if (library?.realm == null) ScriptParsers.install(context, realm.global)
                if (library != null) context.putThreadLocal(scriptLibraryKey, library)
                val scope = if (library == null) realm.global else {
                    val shared = library.scope ?: NativeObject().apply {
                        prototype = realm.global
                        // Like the reference, initialization has no invocation bindings or host capabilities.
                        library.scripts.forEachIndexed { index, code ->
                            evaluateGlobal(context, this, code, "source-library-$index")
                        }
                        sealObject()
                        library.scope = this
                        library.realm = realm
                    }
                    NativeObject().apply { prototype = shared }
                }
                val ruleContext = frame.ruleContext ?: RuleContext(frame.sourceId, frame.bookId, frame.chapterId, frame.baseUrl)
                val bookData = ruleContext.bookMetadata?.let { Json.parseToJsonElement(it).jsonObject } ?: frame.book
                val chapterData = ruleContext.chapterMetadata?.let { Json.parseToJsonElement(it).jsonObject } ?: frame.chapter
                val book = ScriptMetadata.create(context, scope, bookData, frame.bookId, false, ruleContext.bookValues, ruleContext.bookWrites, ruleContext.bookBigValues, ruleContext.bookBigWrites, frame.chineseConverter,
                    "book" !in ruleContext.metadataVariablesInitialized) { ruleContext.metadataVariablesInitialized.add("book") }
                val chapter = ScriptMetadata.create(context, scope, chapterData, frame.chapterId, true, ruleContext.chapterValues, ruleContext.chapterWrites, ruleContext.chapterBigValues, ruleContext.chapterBigWrites, frame.chineseConverter,
                    "chapter" !in ruleContext.metadataVariablesInitialized) { ruleContext.metadataVariablesInitialized.add("chapter") }
                ruleContext.initializeMetadataVariables = {
                    ScriptableObject.getProperty(book, "variableMap")
                    ScriptableObject.getProperty(chapter, "variableMap")
                }
                scope.put("book", scope, book)
                scope.put("chapter", scope, chapter)
                scope.put("result", scope, JsonScriptData(context, scope, limits.maxBridgeChars).convert(frame.variables["result"] ?: JsonNull))
                scope.put("key", scope, frame.key)
                scope.put("page", scope, frame.page)
                scope.put("baseUrl", scope, frame.baseUrl)
                context.putThreadLocal(bridgeLimitKey, limits.maxBridgeChars)
                ScriptBridge(bridge, ScriptRuleHelpers(scope, frame.copy(ruleContext = ruleContext), limits), ScriptRequestTemplates(scope, frame), archives).install(context, scope, frame)
                val value = try { evaluateGlobal(context, scope, source, "source-script") }
                    finally { ruleContext.initializeMetadataVariables = null }
                frame.discovery?.capture()
                ruleContext.bookMetadata = ScriptMetadata.capture(book, limits.maxBridgeChars).toString()
                ruleContext.chapterMetadata = ScriptMetadata.capture(chapter, limits.maxBridgeChars).toString()
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                val json = BoundedJsonResult(limits.maxResultChars).encode(value)
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                ScriptResult.Success(json)
            }
        } catch (_: ScriptBudgetExceeded) { ScriptResult.Failure(FailureCode.Timeout, "instruction budget exceeded") }
          catch (_: ScriptCancelled) { ScriptResult.Failure(FailureCode.Cancelled, "script cancelled") }
          catch (_: SerializationCancelled) { ScriptResult.Failure(FailureCode.Cancelled, "script cancelled") }
          catch (_: ResultTooLarge) { ScriptResult.Failure(FailureCode.ResultTooLarge, "result too large") }
          catch (_: UnsupportedResult) { ScriptResult.Failure(FailureCode.UnsupportedResult, "result is not JSON data") }
          catch (_: WrappedException) { ScriptResult.Failure(FailureCode.BridgeDenied, "host bridge denied") }
          catch (_: ScriptSyntaxError) { ScriptResult.Failure(FailureCode.Syntax, "syntax error") }
          catch (_: EvaluatorException) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
          catch (_: StackOverflowError) { ScriptResult.Failure(FailureCode.Runtime, "script stack exhausted") }
          catch (_: BridgeRejected) { ScriptResult.Failure(FailureCode.BridgeDenied, "host bridge denied") }
          catch (_: RequestRejected) { ScriptResult.Failure(FailureCode.RequestSyntax, "invalid request options") }
          catch (_: JavaScriptException) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
          catch (_: Exception) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
    }
}
