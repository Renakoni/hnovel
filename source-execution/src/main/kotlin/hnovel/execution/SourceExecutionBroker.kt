package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Host-owned per-invocation capability. A script cannot choose its session, identity or grants. */
class SourceExecutionBroker(val identity: ExecutionIdentity, private val authority: ExecutionAuthority,
    private val session: SourceSession, val limits: ExecutionLimits,
    private val baseUrl: String = "", private val keyword: String = "", private val page: Int = 1) : AutoCloseable {
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requests = 0
    private var closed = false

    init {
        require(identity.namespace == session.scope.namespace && identity.sourceId == session.scope.sourceId &&
            identity.profile == session.scope.profile && identity.accountGeneration == session.scope.accountGeneration)
        check(authority.accepts(identity) && !session.closed)
    }

    /** The script-visible context and request compiler must describe the same invocation. */
    fun matchesTaskContext(task: ExecutionTask): Boolean = when (task) {
        is ExecutionTask.Script -> baseUrl == task.baseUrl && keyword == task.key && page == task.page
        is ExecutionTask.Rule -> baseUrl == task.baseUrl && keyword == task.key && page == task.page
        else -> true
    }

    private fun <T> authorized(block: () -> T): T = authority.authorized(identity) {
        synchronized(this) {
            check(!closed && !session.closed) { "Execution retired" }
            block()
        }
    }

    suspend fun call(name: String, args: List<JsonElement>): JsonElement {
        val requestNumber = reserveRequest()
        return ownedWork {
            when (name) {
                "java.ajax" -> {
                    require(args.size == 1)
                    val compiled = RequestCompiler().compile("script-$requestNumber", args[0].jsonPrimitive.content,
                        baseUrl, keyword, page)
                    check(compiled is CompiledRequest.Ready) { "Request requires an unsupported option" }
                    val response = session.execute(compiled.request.copy(timeoutMillis = limits.timeoutMillis),
                        RequestCommitGuard { action -> authorized(action) })
                    check(response is BrokerResult.Success) { "Broker request failed" }
                    JsonPrimitive(response.response.text())
                }
                "cache.get", "source.get", "source.getVariable" -> authorized {
                    require(args.size == if (name == "source.getVariable") 0 else 1)
                    val key = if (name == "source.getVariable") "variable" else "value:" + args[0].jsonPrimitive.content
                    val area = if (name.startsWith("cache.")) StorageArea.Cache else StorageArea.Config
                    val stored = session.read(StorageRequest(area, key))
                    check(stored is StorageResult.Value) { "Storage read failed" }
                    stored.value?.let(::JsonPrimitive) ?: if (area == StorageArea.Config) JsonPrimitive("") else JsonNull
                }
                "cache.put", "source.put", "cache.delete", "source.setVariable" -> authorized {
                    val variable = name == "source.setVariable"
                    val deletion = name == "cache.delete"
                    require(args.size == (if (variable || deletion) 1 else 2) || name == "cache.put" && args.size == 3)
                    val key = if (variable) "variable" else "value:" + args[0].jsonPrimitive.content
                    val value = if (deletion) null else args[if (variable) 0 else 1].let {
                        if (it == JsonNull) null else it.jsonPrimitive.content
                    }
                    val ttl = if (name == "cache.put" && args.size == 3) Math.multiplyExact(args[2].jsonPrimitive.long, 1000) else null
                    val area = if (name.startsWith("cache.")) StorageArea.Cache else StorageArea.Config
                    check(session.write(StorageRequest(area, key, value, ttl)) is StorageResult.Value) { "Storage write failed" }
                    if (name == "source.put") JsonPrimitive(value.orEmpty()) else JsonNull
                }
                else -> error("Unknown bridge operation")
            }
        }
    }

    /** Downloads data only; library code is evaluated exclusively in the isolated worker. */
    suspend fun loadLibrary(definition: String): List<String> = ownedWork {
        var size = 0L
        SourceLibraryDefinition.urls(definition).map { url ->
            val requestNumber = reserveRequest()
            check(session.permissionFailure(url) == null) { "Library origin denied" }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val key = "library:$digest"
            val cached = authorized { session.read(StorageRequest(StorageArea.Account, key)) }
            check(cached is StorageResult.Value) { "Library cache unavailable" }
            val entry = cached.value?.let { Json.parseToJsonElement(it).jsonObject } ?: run {
                val response = session.execute(BrokerRequest("library-$requestNumber", url,
                    timeoutMillis = limits.timeoutMillis, kind = ResourceKind.Script), RequestCommitGuard { action -> authorized(action) })
                check(response is BrokerResult.Success && response.response.status in 200..299) { "Library download failed" }
                val code = response.response.text()
                if (code.length.toLong() + 1 + size > SourceLibraryDefinition.MAX_CHARS) throw LibraryTooLarge()
                buildJsonObject { put("url", response.response.finalUrl); put("code", code) }.also { entry ->
                    authorized {
                        check(session.write(StorageRequest(StorageArea.Account, key, entry.toString())) is StorageResult.Value) {
                            "Library cache quota exceeded"
                        }
                    }
                }
            }
            check(session.permissionFailure(entry.getValue("url").jsonPrimitive.content) == null) { "Library redirect origin denied" }
            entry.getValue("code").jsonPrimitive.content.also { code ->
                size += code.length.toLong() + 1
                if (size > SourceLibraryDefinition.MAX_CHARS) throw LibraryTooLarge()
            }
        }
    }

    private fun reserveRequest(): Int = authorized {
        check(++requests <= limits.maxRequests) { "Request budget exceeded" }
        requests
    }

    private suspend fun <T> ownedWork(block: suspend () -> T): T {
        authorized { }
        val work = lifetime.async { block() }
        return try { work.await().let { value -> authorized { value } } } finally { work.cancel() }
    }

    @Synchronized override fun close() {
        closed = true
        lifetime.cancel()
    }
}
