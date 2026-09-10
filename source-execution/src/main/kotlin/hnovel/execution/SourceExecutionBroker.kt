package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        authority.bindSession(identity, session)
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
                "resource.storeArchive", "resource.readArchive" -> archiveResource(name, args)
                "java.importScript", "java.cacheFile", "java.downloadFile", "java.readFile", "java.readTxtFile", "java.deleteFile" ->
                    resource(name, args, requestNumber)
                "java.androidId" -> authorized {
                    require(args.isEmpty())
                    val value = session.installationIdentifier()
                    check(value is StorageResult.Value && value.value != null) { "Source identifier unavailable" }
                    JsonPrimitive(value.value)
                }
                "java.ajax" -> {
                    require(args.size == 1)
                    val url = (args[0] as? JsonArray)?.firstOrNull() ?: args[0]
                    JsonPrimitive(fetch(compiled(requestNumber, url.jsonPrimitive.content)).text())
                }
                "java.connect" -> {
                    require(args.size in 1..2)
                    val headers = args.getOrNull(1)?.takeUnless { it == JsonNull }?.let {
                        check(it is JsonPrimitive && it.isString)
                        // Use the same bounded JSON parser as other reverse IPC input.
                        headerMap(BridgeWire.arguments("[${it.content}]".toByteArray()).single())
                    }.orEmpty()
                    val request = compiled(requestNumber, args[0].jsonPrimitive.content, headers)
                    snapshot(fetch(request, hnovel.rhino.ScriptLimits.DEFAULT_BRIDGE_CHARS), false)
                }
                "java.ajaxAll" -> {
                    require(args.size == 1)
                    val urls = args[0].jsonArray
                    val requests = urls.mapIndexed { index, url ->
                        compiled(if (index == 0) requestNumber else reserveRequest(), url.jsonPrimitive.content)
                    }
                    // Reserve and compile the complete batch before dispatch; a rejected budget
                    // cannot send a prefix. Session permits bound actual network concurrency.
                    val responseBytes = java.util.concurrent.atomic.AtomicLong(2)
                    val decoding = Semaphore(4)
                    coroutineScope {
                        val pending = requests.map { request -> async {
                            decoding.withPermit {
                                snapshot(fetch(request, hnovel.rhino.ScriptLimits.DEFAULT_BRIDGE_CHARS), false).also {
                                    check(responseBytes.addAndGet(it.toString().toByteArray().size.toLong() + 1) <= BridgeWire.MAX_BYTES) { "Batch response too large" }
                                }
                            }
                        } }
                        JsonArray(pending.awaitAll())
                    }
                }
                "java.get", "java.head", "java.post" -> {
                    val post = name == "java.post"
                    require(args.size == if (post) 3 else 2)
                    val request = BrokerRequest("script-$requestNumber", args[0].jsonPrimitive.content,
                        method = name.substringAfter('.').uppercase(), headers = headerMap(args[if (post) 2 else 1]),
                        body = if (post) args[1].jsonPrimitive.content else null, followRedirects = false)
                    snapshot(fetch(request, hnovel.rhino.ScriptLimits.DEFAULT_BRIDGE_CHARS), true)
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

    private fun compiled(number: Int, rule: String, headers: Map<String, String> = emptyMap()): BrokerRequest {
        val compiled = RequestCompiler().compile("script-$number", rule, baseUrl, keyword, page, headers)
        check(compiled is CompiledRequest.Ready) { "Request requires an unsupported option" }
        return compiled.request
    }

    private fun headerMap(value: JsonElement): Map<String, String> = value.jsonObject.mapValues { (_, item) ->
        check(item is JsonPrimitive && item.isString)
        item.content
    }

    private suspend fun fetch(request: BrokerRequest, maxResponseBytes: Int = BridgeWire.MAX_BYTES): BrokerResponse {
        val result = session.execute(request.copy(timeoutMillis = limits.timeoutMillis, maxResponseBytes = maxResponseBytes), RequestCommitGuard { action -> authorized(action) })
        check(result is BrokerResult.Success) { "Broker request failed" }
        return result.response
    }

    private fun snapshot(response: BrokerResponse, binary: Boolean) = buildJsonObject {
        if (binary) put("bytes", java.util.Base64.getEncoder().encodeToString(response.body))
        else {
            put("body", response.text())
            put("bodySize", response.body.size)
        }
        put("url", response.finalUrl)
        put("status", response.status)
        put("message", response.message)
        put("headers", JsonObject(response.headers.mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }))
        put("charset", response.declaredCharset?.let(::JsonPrimitive) ?: JsonNull)
        put("method", response.method)
        put("protocol", response.protocol)
        put("sentAt", response.sentAt)
        put("receivedAt", response.receivedAt)
    }

    /** Logical source/account resources. A script path is never passed to the host filesystem. */
    private suspend fun resource(name: String, args: List<JsonElement>, number: Int): JsonElement {
        require(args.size in 1..if (name in setOf("java.downloadFile", "java.cacheFile", "java.readTxtFile")) 2 else 1)
        val first = args[0].jsonPrimitive.content
        if (first.removePrefix("/").startsWith("archives/") && name in setOf("java.readFile", "java.readTxtFile", "java.deleteFile"))
            return archiveResource(name, args)
        fun key(path: String): String {
            require(path.matches(Regex("/?resources/[0-9a-f]{64}\\.[a-zA-Z0-9]{1,12}"))) { "Invalid resource path" }
            return "resource:" + path.removePrefix("/")
        }
        fun read(path: String): JsonObject? = authorized {
            val stored = session.read(StorageRequest(StorageArea.Account, key(path)))
            check(stored is StorageResult.Value) { "Resource storage unavailable" }
            stored.value?.let { Json.parseToJsonElement(it).jsonObject }
        }
        fun checkGrants(record: JsonObject) = authorized {
            for (field in listOf("url", "finalUrl")) check(session.permissionFailure(record.getValue(field).jsonPrimitive.content) == null) { "Resource origin denied" }
        }
        fun bytes(record: JsonObject) = java.util.Base64.getDecoder().decode(record.getValue("bytes").jsonPrimitive.content)
        fun text(record: JsonObject, charset: String? = null): String = bytes(record).toString(java.nio.charset.Charset.forName(
            charset ?: record.getValue("charset").jsonPrimitive.content))
        if (name in setOf("java.readFile", "java.readTxtFile", "java.deleteFile") || name == "java.importScript" && !first.startsWith("http", true)) {
            val record = read(first)
            if (name == "java.deleteFile") return authorized {
                check(session.write(StorageRequest(StorageArea.Account, key(first))) is StorageResult.Value)
                JsonPrimitive(record != null)
            }
            if (record == null) {
                check(name != "java.importScript") { "Script resource missing" }
                return if (name == "java.readFile") JsonNull else JsonPrimitive("")
            }
            checkGrants(record)
            if (name == "java.readFile") return JsonArray(bytes(record).map { JsonPrimitive(it.toInt()) })
            return JsonPrimitive(text(record, args.getOrNull(1)?.jsonPrimitive?.content).also {
                check(name != "java.importScript" || it.isNotBlank()) { "Script resource empty" }
            })
        }
        val rawUrl = if (name == "java.downloadFile" && args.size == 2) args[1].jsonPrimitive.content else first
        val optionStart = Regex(",\\s*(?=\\{)").find(rawUrl)
        val options = optionStart?.let { BridgeWire.arguments(("[" + rawUrl.substring(it.range.last + 1) + "]").toByteArray()).single().jsonObject }
        val type = options?.get("type")?.jsonPrimitive?.content
        if (name == "java.downloadFile" && args.size == 2 && type == null) return JsonPrimitive("")
        val suffix = type ?: rawUrl.substring(0, optionStart?.range?.first ?: rawUrl.length).substringBefore('?').substringAfterLast('/', "").substringAfterLast('.', "bin")
        require(suffix.matches(Regex("[a-zA-Z0-9]{1,12}"))) { "Invalid resource type" }
        val rule = if (options != null) rawUrl.substring(0, optionStart!!.range.first) + "," + JsonObject(options - "type") else rawUrl
        val request = compiled(number, rule).copy(kind = ResourceKind.Script)
        authorized { check(session.permissionFailure(request.url) == null) { "Resource origin denied" } }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(rawUrl.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val path = "/resources/$hash.$suffix"
        val cache = name == "java.cacheFile" || name == "java.importScript"
        val seconds = if (name == "java.cacheFile") args.getOrNull(1)?.jsonPrimitive?.long ?: 0 else 0
        require(seconds >= 0)
        val cacheDuration = Math.multiplyExact(seconds, 1000)
        if (cache) read(path)?.let { record ->
            val until = record["cacheUntil"]?.jsonPrimitive?.long
            if (until != null && (until == 0L || until > System.currentTimeMillis())) {
                checkGrants(record)
                return JsonPrimitive(text(record).also { check(name != "java.importScript" || it.isNotBlank()) })
            }
        }
        val response = if (name == "java.downloadFile" && args.size == 2) {
            val hex = first
            require(hex.length % 2 == 0 && hex.length <= BridgeWire.MAX_BYTES * 2)
            val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            BrokerResponse(200, request.url, emptyMap(), data, "UTF-8", 0)
        } else fetch(request).also { check(it.status in 200..299) { "Resource download failed" } }
        val record = buildJsonObject {
            put("url", request.url); put("finalUrl", response.finalUrl); put("charset", response.charset)
            put("bytes", java.util.Base64.getEncoder().encodeToString(response.body))
            if (cache) put("cacheUntil", if (seconds == 0L) 0 else Math.addExact(System.currentTimeMillis(), cacheDuration))
        }
        authorized { check(session.write(StorageRequest(StorageArea.Account, key(path), record.toString())) is StorageResult.Value) { "Resource quota exceeded" } }
        return if (name == "java.downloadFile") JsonPrimitive(path) else JsonPrimitive(response.text().also {
            check(name != "java.importScript" || it.isNotBlank()) { "Script resource empty" }
        })
    }

    /** One account-storage record per extracted archive makes publication and quota failure atomic. */
    private fun archiveResource(name: String, args: List<JsonElement>): JsonElement = authorized {
        require(args.size == if (name == "resource.storeArchive") 2 else 1)
        val path = args[0].jsonPrimitive.content.removePrefix("/")
        fun read(key: String): JsonObject? {
            val value = session.read(StorageRequest(StorageArea.Account, key))
            check(value is StorageResult.Value)
            return value.value?.let { Json.parseToJsonElement(it).jsonObject }
        }
        fun grants(record: JsonObject) {
            for (field in listOf("url", "finalUrl")) check(session.permissionFailure(record.getValue(field).jsonPrimitive.content) == null)
        }
        if (name == "resource.storeArchive") {
            require(path.matches(Regex("resources/[0-9a-f]{64}\\.[a-zA-Z0-9]{1,12}")))
            val original = read("resource:$path") ?: error("Archive missing")
            grants(original)
            val files = args[1].jsonObject
            require(files.size <= 256)
            var size = 0L
            files.forEach { (entry, value) ->
                hnovel.rhino.ArchiveDecoder.validatePath(entry)
                require(value.jsonPrimitive.isString)
                size += java.util.Base64.getDecoder().decode(value.jsonPrimitive.content).size
                require(size <= BridgeWire.MAX_BYTES)
            }
            // Each extraction owns a distinct record, even for the same downloaded URL.
            val directory = "archives/" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val record = buildJsonObject {
                put("url", original.getValue("url")); put("finalUrl", original.getValue("finalUrl")); put("files", files)
            }
            check(session.write(StorageRequest(StorageArea.Account, "resource:$directory", record.toString())) is StorageResult.Value)
            return@authorized JsonPrimitive("/$directory")
        }
        val match = Regex("(archives/[0-9a-f]{64})(?:/(.+))?").matchEntire(path) ?: error("Invalid archive path")
        val directory = match.groupValues[1]
        val entry = match.groupValues[2].takeIf(String::isNotEmpty)
        entry?.let(hnovel.rhino.ArchiveDecoder::validatePath)
        val record = read("resource:$directory")
        if (name == "java.deleteFile") {
            if (record == null) return@authorized JsonPrimitive(false)
            val files = record.getValue("files").jsonObject
            if (entry != null && entry !in files) return@authorized JsonPrimitive(false)
            val updated = if (entry == null) null else JsonObject(record + ("files" to JsonObject(files - entry))).toString()
            check(session.write(StorageRequest(StorageArea.Account, "resource:$directory", updated)) is StorageResult.Value)
            return@authorized JsonPrimitive(true)
        }
        if (record == null) return@authorized JsonNull
        grants(record)
        val files = record.getValue("files").jsonObject
        if (name == "resource.readArchive") { require(entry == null); return@authorized files }
        require(entry != null)
        val content = files[entry] ?: return@authorized JsonNull
        val bytes = java.util.Base64.getDecoder().decode(content.jsonPrimitive.content)
        if (name == "java.readFile") JsonArray(bytes.map { JsonPrimitive(it.toInt()) }) else JsonPrimitive(bytes.toString(Charsets.UTF_8))
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
