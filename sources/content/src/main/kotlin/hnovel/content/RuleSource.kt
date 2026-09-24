package hnovel.content

import hnovel.execution.*
import hnovel.imports.SourceDefinition
import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.*
import hnovel.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

private const val DIRECTORY_MAX_CHAPTERS = 50_000
private const val DIRECTORY_MAX_PAGES = 2048
private const val DIRECTORY_TIMEOUT_MILLIS = 600_000L

/** One registered revision/account. Source retirement revokes its ticket; it never chooses another source. */
class RuleSource(val definition: SourceDefinition, private val identity: ExecutionIdentity,
    private val authority: ExecutionAuthority, private val session: SourceSession,
    private val runner: RuleTaskRunner, private val trace: ContentTrace = ContentTrace.None,
    private val discoveryEnabled: Boolean = definition.enabledExplore) : AutoCloseable {
    internal val spec = RuleSourceDefinition(definition)
    private val store = RuleBookStore(session, authority, identity)
    private val serial = Mutex()
    private var prefetchedDirectoryId: String? = null
    val canSearch get() = spec.searchUrl.isNotBlank()
    val canLogin get() = spec.loginUrl.isNotBlank() || spec.loginUi.isNotBlank()
    private val discoveryCapabilities by lazy { RuleDiscoveryClassifier.capabilities(spec) }
    val canFeed get() = discoveryEnabled && discoveryCapabilities.hasFeed
    val canCategorize get() = discoveryEnabled && discoveryCapabilities.hasCategories
    val canDiscover get() = discoveryEnabled && (spec.exploreUrl.isNotBlank() ||
        spec.homepageModules.isNotBlank() || spec.exploreScreen.isNotBlank() || spec.customButton)

    fun openDiscovery(sessionId: String, values: Map<String, String> = emptyMap(),
        environment: RuleDiscoveryEnvironment = RuleDiscoveryEnvironment()) = RuleDiscoverySession(this, sessionId, values, environment)

    internal fun discoveryState(key: String): String? = authority.authorized(identity) {
        val result = session.read(StorageRequest(StorageArea.Config, "discovery/$key"))
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "discovery.$key")
        result.value
    }

    internal fun saveDiscoveryState(key: String, value: String) = authority.authorized(identity) {
        val path = if (key == "configuration") "variable" else "discovery/$key"
        if (session.write(StorageRequest(StorageArea.Config, path, value)) !is StorageResult.Value)
            throw SourceContentException(ContentError.Storage, "discovery.$key")
    }

    internal suspend fun discoveryPage(context: RuleEvaluation, url: String): List<RuleBook> =
        booksFromPage(context, fetch(context, url, "exploreUrl",
            acceptErrorResponse = spec.explore.string("bookList").isScriptRule()), spec.explore, "ruleExplore")

    suspend fun openDiscoveryBrowser(url: String, html: String? = null, script: String = "", title: String = ""): Unit =
        operation("discovery.browser", timeoutMillis = 300000) {
            val context = evaluation(interactive = true)
            request(context, url, "discovery.browser", browser = BrowserOptions(interactive = true, html = html, script = script, title = title))
        }
    init {
        require(identity.sourceId == definition.sourceId && identity.profile == definition.profile && identity.revision == definition.contentDigest)
        require(session.scope.sourceId == identity.sourceId && session.scope.namespace == identity.namespace &&
            session.scope.profile == identity.profile && session.scope.accountGeneration == identity.accountGeneration)
        session.configureSource(spec.baseUrl, spec.cookiesEnabled, spec.browserRead, spec.concurrentRate,
            spec.localStorageRetention, defaultUserAgent = DESKTOP_USER_AGENT)
    }

    private var cachedLoginForm: LoginForm? = null

    /** A panel owns its form and execution ticket, while sharing the current account storage. */
    fun openLoginSession(): RuleSource {
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, "login")
        val ticket = authority.issue(identity.sourceId, identity.profile, identity.revision, identity.namespace, identity.accountGeneration)
        return RuleSource(definition, ticket, authority, session, runner, trace, discoveryEnabled)
    }

    suspend fun loginForm(): LoginForm = operation("loginUi") {
        (cachedLoginForm ?: loadLoginForm()).withValues(loginValues())
    }

    private fun loginInfo(): String? {
        val stored = session.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)) as? StorageResult.Value
            ?: throw SourceContentException(ContentError.Storage, "loginUi.values")
        return stored.value
    }

    private fun loginValues(): Map<String, String> = LoginInfo.stringFields(loginInfo()).orEmpty()
        .filter { (key, value) -> key.length <= 128 && value.jsonPrimitive.content.length <= 4096 }
        .mapValues { it.value.jsonPrimitive.content }

    private fun saveLoginValues(values: Map<String, String>) = authority.authorized(identity) {
        val original = loginInfo()
        val merged = LoginInfo.merge(original, values)
        if (merged != original)
            check(session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, merged)) is StorageResult.Value)
    }

    private fun loginContext(values: Map<String, String>, interactive: Boolean): RuleEvaluation = evaluation(interactive = interactive).also {
        // Reuse the existing bounded interaction envelope; the login form owns this draft.
        // No exploration catalogue is evaluated and no Android objects cross the worker boundary.
        it.discovery = buildJsonObject {
            put("sessionId", "login"); put("values", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
            put("interactive", interactive); put("noBook", true)
        }
    }

    private fun loginPrelude(): String = if (spec.loginUrl.trim().startsWith("http://", true) ||
        spec.loginUrl.trim().startsWith("https://", true)) "" else scriptBody(spec.loginUrl)

    private suspend fun loginUiValue(context: RuleEvaluation, code: String, field: String): JsonElement {
        val value = context.script("result=JSON.parse(result);\n${loginPrelude()}\nJSON.stringify(eval(${JsonPrimitive(scriptBody(code))}));",
            RuleValue.Text(context.discovery!!.getValue("values").toString()), field).text()
        return Json.parseToJsonElement(value)
    }

    private suspend fun loadLoginForm(): LoginForm {
        val extended = definition.profile == EXTENSION_PROFILE
        val context = loginContext(loginValues().entries.take(128).associate { it.toPair() }, interactive = false)
        val raw = spec.loginUi.trim()
        val ui = if (raw.startsWith("@js:", true) || raw.startsWith("<js>", true)) {
            if (!extended) throw SourceContentException(ContentError.InvalidRule, "loginUi")
            val result = loginUiValue(context, raw, "loginUi")
            if (result is JsonPrimitive && result.isString) result.content else result.toString()
        } else raw
        val form = LoginForm.parse(ui, spec.loginUrl, extended).withValues(loginValues())
        context.discovery = JsonObject(context.discovery!! + ("values" to JsonObject(form.values.mapValues { JsonPrimitive(it.value) })))
        val rendered = form.copy(fields = form.fields.mapIndexed { index, field ->
            if (field.viewName == null) field else {
                val value = loginUiValue(context, field.viewName, "loginUi[$index].viewName") as? JsonPrimitive
                    ?: throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].viewName")
                if (value == JsonNull || value.content.length > 256) throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].viewName")
                field.copy(label = value.content)
            }
        })
        if (context.discovery!!["saveSeconds"]?.let { it != JsonNull } == true)
            throw SourceContentException(ContentError.InvalidRule, "loginUi.infoMap.save")
        return rendered.also { cachedLoginForm = it }
    }

    suspend fun login(values: Map<String, String>, action: String? = null, formId: String? = null): Unit = operation("loginUrl", timeoutMillis = 300000) {
        if (formId != null && cachedLoginForm?.id != formId)
            throw SourceContentException(ContentError.Unavailable, "loginUi")
        val form = (cachedLoginForm ?: loadLoginForm()).withValues(loginValues())
        // Trusted callers without a UI snapshot retain the unique-name API. UI submissions
        // always carry a form ID and can only address an opaque control ID from that form.
        val fieldAction = action?.let { id -> form.fields.singleOrNull { if (formId == null) it.name == id else it.id == id }?.action
            ?: throw SourceContentException(ContentError.InvalidRule, "loginUi.action") }
        form.validate(values)
        val submitted = form.values + values
        form.validate(submitted, allowAdditional = true)
        val info = JsonObject(submitted.mapValues { JsonPrimitive(it.value) }).toString()
        saveLoginValues(submitted)
        val context = loginContext(submitted, interactive = true)
        if (form.browserUrl != null && action == null) {
            val pending = if (spec.browserRead) (session.read(StorageRequest(StorageArea.Account,
                StorageRequestKey.BROWSER_PENDING_URL)) as? StorageResult.Value)?.value else null
            val response = session.execute(BrokerRequest("login", pending ?: form.browserUrl, headers = context.headers(),
                timeoutMillis = 60000, browser = BrowserOptions(interactive = true)),
                RequestCommitGuard { authority.authorized(identity, it) })
            when (response) {
                is BrokerResult.Failure -> throw SourceContentException(response.code.contentError(), "loginUrl", response.denial,
                    verification = verification(response))
                is BrokerResult.Success -> checkStatus(response.response.status, "loginUrl", response.response.kind == ResponseKind.BrowserDocument)
            }
            // Closing a website preserves its session; HTTP 200 alone does not verify a login.
            authority.authorized(identity) {
                check(session.write(StorageRequest(StorageArea.Account, "login/status", "session")) is StorageResult.Value)
                if (pending != null) check(session.write(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)) is StorageResult.Value)
            }
            return@operation
        }
        val code = if (action == null) "if(typeof login!=='function')throw new Error('login missing');login();true;"
            else fieldAction!!
        if (code.startsWith("http://", true) || code.startsWith("https://", true)) {
            val response = request(context, code, "loginUi.action", browser = BrowserOptions(interactive = true))
            checkStatus(response.status, "loginUi.action", response.kind == ResponseKind.BrowserDocument)
        } else context.script("result=JSON.parse(result);\n${loginPrelude()}\n${scriptBody(code)}", RuleValue.Text(info),
            if (action == null) "loginUrl" else "loginUi.action")
        val state = context.discovery!!
        if (state["saveSeconds"]?.let { it != JsonNull } == true) throw SourceContentException(ContentError.InvalidRule, "loginUi.infoMap.save")
        val actions = (state["actions"] as? JsonArray).orEmpty()
        for (item in actions) {
            val command = item.jsonObject
            when (command.string("kind")) {
                "refresh" -> Unit
                "showBrowser" -> {
                    val args = command.getValue("args").jsonArray
                    if (args.size !in 1..4) throw SourceContentException(ContentError.InvalidRule, "loginUi.action.browser")
                    fun arg(index: Int) = args.getOrNull(index)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                    val config = arg(3)?.let { Json.parseToJsonElement(it).jsonObject }
                    if (config?.keys?.any { it != "title" } == true) throw SourceContentException(ContentError.InvalidRule, "loginUi.action.browser")
                    val response = request(context, arg(0)!!, "loginUi.action.browser", browser = BrowserOptions(interactive = true,
                        html = arg(1), script = arg(2).orEmpty(), title = config?.string("title").orEmpty()))
                    checkStatus(response.status, "loginUi.action.browser", response.kind == ResponseKind.BrowserDocument)
                }
                else -> throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
            }
        }
        val changed = state.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
        form.validate(changed, allowAdditional = true)
        if (changed != submitted) saveLoginValues(changed.filter { (key, value) -> submitted[key] != value })
        if (actions.any { it.jsonObject.string("kind") == "refresh" }) cachedLoginForm = null
        if (action == null) authority.authorized(identity) { check(session.write(StorageRequest(StorageArea.Account, "login/status", "authenticated")) is StorageResult.Value) }
    }

    suspend fun search(keyword: String, page: Int = 1): List<RuleBook> = operation("ruleSearch") {
        if (!canSearch) throw SourceContentException(ContentError.MissingCapability, "searchUrl")
        val context = evaluation(keyword = keyword, page = page)
        val document = fetch(context, spec.searchUrl, "searchUrl",
            acceptErrorResponse = spec.search.string("bookList").isScriptRule())
        booksFromPage(context, document, spec.search, "ruleSearch")
    }

    /** Executes a selected discovery URL through the same production list pipeline as search. */
    suspend fun discovery(url: String, page: Int = 1): List<RuleBook> = operation("ruleExplore") {
        if (url.isBlank() || spec.explore.isEmpty()) throw SourceContentException(ContentError.MissingCapability, "ruleExplore")
        val context = evaluation(page = page)
        discoveryPage(context, url)
    }

    private suspend fun booksFromPage(context: RuleEvaluation, document: PageDocument, fields: JsonObject,
        field: String): List<RuleBook> {
        val rule = fields.string("bookList")
        val isBookUrl = spec.bookUrlPattern.isNotBlank() && context.value(":\\A(?:${spec.bookUrlPattern})\\z",
            RuleValue.Text(document.url), "bookUrlPattern", OutputKind.Elements).items().isNotEmpty()
        if (rule.isBlank() || isBookUrl) {
            val id = sourceLink(document.url, document.url)
            val record = information(id, BookRecord(identity.revision, RuleBook(id, state = context.book)), document)
            store.write(record)
            return listOf(record.book)
        }
        val items = context.value(rule.removePrefix("-").removePrefix("+"), document.input(), "$field.bookList", OutputKind.Elements).items()
        val books = mutableListOf<RuleBook>()
        for (item in items) {
            val row = context.fork()
            val parsed = bookFields(row, item, fields, field, RuleBook(""))
            if (parsed.title.isBlank()) continue
            val rawUrl = row.url(fields.string("bookUrl"), item, "$field.bookUrl")
            val id = sourceLink(document.url, rawUrl.ifBlank { document.url })
            row.bookId = id; row.bookField("bookUrl", id)
            books += parsed.copy(id = id, tocUrl = id, state = row.book)
        }
        val ordered = (if (rule.startsWith('-')) books.reversed() else books).distinctBy { it.id }
        store.write(ordered.mapNotNull { book ->
            val old = store.read(book.id)
            if (old?.informationLoaded != true || old.revision != identity.revision)
                BookRecord(identity.revision, book) else null
        })
        return ordered
    }

    suspend fun information(bookId: String): RuleBook = operation("ruleBookInfo", timeoutMillis = DIRECTORY_TIMEOUT_MILLIS) {
        prefetchedDirectoryId = null
        val id = sourceLink(spec.baseUrl, bookId)
        val old = store.read(id)
        var refreshed = information(id, old)
        // Sources without an update marker still participate in host background update checks.
        val inferUpdate = refreshed.book.latestChapter.isBlank() && refreshed.book.updateTime.isBlank()
        if (inferUpdate) {
            refreshed = directory(refreshed)
            if (old?.chapters?.map { it.id } != refreshed.chapters.map { it.id })
                refreshed = refreshed.copy(book = refreshed.book.copy(observedUpdate = System.currentTimeMillis()))
        }
        store.write(refreshed)
        prefetchedDirectoryId = if (inferUpdate) id else null
        refreshed.book
    }

    suspend fun directory(bookId: String): List<RuleChapter> = operation("ruleToc", timeoutMillis = DIRECTORY_TIMEOUT_MILLIS) {
        val id = sourceLink(spec.baseUrl, bookId)
        val prefetched = prefetchedDirectoryId == id
        prefetchedDirectoryId = null
        val book = record(id)
        if (prefetched) book.chapters else directory(book).also(store::write).chapters
    }

    suspend fun content(bookId: String, chapterId: String): RuleContent = operation("ruleContent", timeoutMillis = DIRECTORY_TIMEOUT_MILLIS) {
        val id = sourceLink(spec.baseUrl, bookId)
        var record = record(id)
        if (record.chapters.isEmpty()) record = directory(record)
        val index = record.chapters.indexOfFirst { it.id == chapterId && !it.isVolume }
        if (index < 0) throw SourceContentException(ContentError.Unavailable, "chapter")
        val chapter = record.chapters[index]
        val previous = record.chapters.take(index).lastOrNull { !it.isVolume }?.id
        val next = record.chapters.drop(index + 1).firstOrNull { !it.isVolume }?.id
        val context = evaluation(record.book, chapter)
        context.nextChapterUrl = next
        val rule = spec.content.string("content")
        if (rule.isBlank()) throw SourceContentException(ContentError.MissingCapability, "ruleContent.content")
        val queue = ArrayDeque<String>().apply { add(chapter.id) }
        val visited = linkedSetOf<String>()
        val pages = mutableListOf<String>()
        var firstPage: PageDocument? = null
        var completePageList = false
        var title = chapter.title
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            // A repeated link terminates a page chain; never read the next logical chapter here.
            if (url == next || url in visited) continue
            visit(visited, url, "ruleContent.nextContentUrl")
            val browser = if (spec.content.string("webJs").isNotBlank() || spec.content.string("sourceRegex").isNotBlank())
                BrowserOptions(script = spec.content.string("webJs"), sourceRegex = spec.content.string("sourceRegex"), nativeWebsite = true) else null
            // Script-led content rules can use a placeholder chapter URL and perform their
            // own requests. Like WebBook, pass the HTTP response body into those scripts.
            val document = fetch(context, url, "ruleContent.content", browser, acceptErrorResponse = rule.isScriptRule())
            // Redirects cannot turn a continuation (or the first page) into the next logical chapter.
            if (document.url == next) continue
            if (!document.inline && document.url != url && !visited.add(document.url)) throw SourceContentException(ContentError.RepeatedPage, "ruleContent.nextContentUrl")
            if (firstPage == null) firstPage = document
            val html = context.text(rule, document.input(), "ruleContent.content", unescape = false)
            // BookContent formats and decodes each page before whole-chapter replacement.
            // Reuse the ported HtmlFormatter and resolve images before joining different page bases.
            val normalized = context.text("""
                @js:(function(){var doc=org.jsoup.Jsoup.parse(result,baseUrl);doc.outputSettings().prettyPrint(false);
                    var body=doc.body();body.select('script,style,noscript').remove();body.select('img[src]').forEach(function(img){
                    img.attr('src',img.absUrl('src'))});return java.htmlFormat(body.html())})()
            """.trimIndent(), RuleValue.Text(html), "ruleContent.images")
            pages += normalized
            if (pages.sumOf { it.length.toLong() } > 512000) throw SourceContentException(ContentError.Limit, "ruleContent.content")
            if (!completePageList) {
                val following = links(context, spec.content.string("nextContentUrl"), document, "ruleContent.nextContentUrl")
                completePageList = context.page == 1 && following.size > 1
                (if (completePageList) following else following.take(1)).forEach(queue::addLast)
            }
            context.page++
        }
        if (pages.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleContent.content")
        // BookContent evaluates title and whole-chapter replacement on the first page,
        // after every content page has written its book/chapter variables.
        val initial = checkNotNull(firstPage)
        context.baseUrl = initial.ruleUrl
        context.page = 1
        val extracted = try { context.text(spec.content.string("title"), initial.input(), "ruleContent.title") }
        catch (failure: SourceContentException) {
            if (failure.code != ContentError.InvalidRule) throw failure
            ""
        }
        extracted.takeIf { it.isNotBlank() }?.let { title = it; context.chapterField("title", JsonPrimitive(it)) }
        var merged = pages.joinToString("\n")
        val replacement = spec.content.string("replaceRegex")
        if (replacement.isNotBlank()) merged = context.text(replacement,
            RuleValue.Text(merged.lines().joinToString("\n") { it.trim() }), "ruleContent.replaceRegex", unescape = false)
        val parts = context.markup(merged).items().map {
            Json.decodeFromString(ContentPart.serializer(), it.text())
        }.map { part -> if (part.image != null) part.copy(image = sourceLink(context.baseUrl, part.image)) else part }
        if (parts.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleContent.content")
        title = context.chapter.metadata["title"]?.jsonPrimitive?.content ?: title
        val updated = chapter.copy(title = title, state = context.chapter)
        store.write(record.copy(book = record.book.copy(state = context.book),
            chapters = record.chapters.toMutableList().apply { set(index, updated) }))
        RuleContent(chapter.id, title, parts, previous, next)
    }

    suspend fun image(bookId: String, url: String, cover: Boolean): ByteArray {
        // Rule preparation still owns the worker; waiting for image bytes does not hold the book queue.
        val (context, request, knownCover) = operation("image") {
            val id = sourceLink(spec.baseUrl, bookId)
            val book = store.read(id)?.book ?: RuleBook(id)
            val context = evaluation(book)
            Triple(context, prepareRequest(context, sourceLink(id, url), "image", ResourceKind.Image),
                cover && book.coverUrl.isNotBlank() && book.coverUrl == url)
        }
        val response = executeRequest(request, "image", publicImage = knownCover && request.method == "GET" &&
            request.body == null && request.browser == null)
        val decode = if (cover) spec.coverDecode else spec.content.string("imageDecode")
        if (decode.isBlank()) return response.body
        return operation("imageDecode") {
            // A single numeric array avoids one serialized RuleValue object per image byte.
            val input = JsonArray(response.body.map { JsonPrimitive(it.toInt()) }).toString()
            if (input.length > 196608) throw SourceContentException(ContentError.Limit, "imageDecode")
            val bytes = context.script("var src=${JsonPrimitive(response.finalUrl)};result=JSON.parse(result);" +
                "JSON.stringify(eval(${JsonPrimitive(scriptBody(decode))}))",
                RuleValue.Text(input), if (cover) "coverDecodeJs" else "ruleContent.imageDecode")
            val values = Json.parseToJsonElement(bytes.text()).jsonArray.map { it.jsonPrimitive.intOrNull
                ?: throw SourceContentException(ContentError.InvalidRule, "imageDecode") }
            if (values.any { it !in -128..255 }) throw SourceContentException(ContentError.InvalidRule, "imageDecode")
            values.map { it.toByte() }.toByteArray()
        }
    }

    private suspend fun information(id: String, old: BookRecord?, supplied: PageDocument? = null): BookRecord {
        val initial = old?.book?.let { if (old.revision == identity.revision) it else it.copy(state = ScriptState()) } ?: RuleBook(id)
        val context = evaluation(initial)
        val document = supplied ?: fetch(context, id, "ruleBookInfo")
        context.baseUrl = document.ruleUrl
        val init = spec.information.string("init")
        val input = if (init.isBlank()) document.input() else context.value(init, document.input(), "ruleBookInfo.init", OutputKind.Element)
        var book = bookFields(context, input, spec.information, "ruleBookInfo", initial)
        if (book.title.isBlank()) throw SourceContentException(ContentError.EmptyContent, "ruleBookInfo.name")
        val toc = context.url(spec.information.string("tocUrl"), input, "ruleBookInfo.tocUrl")
        val tocUrl = sourceLink(document.url, toc.ifBlank { id })
        context.bookField("tocUrl", tocUrl)
        val changed = old?.informationLoaded != true || initial.latestChapter != book.latestChapter || initial.updateTime != book.updateTime
        book = book.copy(tocUrl = tocUrl, state = context.book,
            observedUpdate = if (changed) System.currentTimeMillis() else initial.observedUpdate)
        return BookRecord(identity.revision, book, true, document, old?.takeIf { it.revision == identity.revision }?.chapters.orEmpty())
    }

    private suspend fun bookFields(context: RuleEvaluation, input: RuleValue, rules: JsonObject, prefix: String, seed: RuleBook): RuleBook {
        val priorTitle = context.book.metadata["name"]?.jsonPrimitive?.content ?: seed.title
        val priorAuthor = context.book.metadata["author"]?.jsonPrimitive?.content ?: seed.author
        // BookList creates an empty SearchBook before evaluating its first field.
        context.bookField("name", priorTitle)
        suspend fun field(name: String, prior: String, metadata: String = name): String {
            val extracted = try {
                if (name == "kind") context.value(rules.string(name), input, "$prefix.$name", OutputKind.TextList)
                    .items().joinToString(",") { it.text() }
                else context.text(rules.string(name), input, "$prefix.$name")
            } catch (failure: SourceContentException) {
                // BookList/BookInfo tolerate optional metadata errors. Keep the trace, and
                // still surface cancellation, limits, missing dependencies and login/permissions.
                if (name !in setOf("kind", "wordCount", "lastChapter", "intro", "coverUrl") || failure.code != ContentError.InvalidRule) throw failure
                ""
            }
            val value = extracted.ifBlank {
                context.book.metadata[metadata]?.jsonPrimitive?.content ?: prior
            }
            context.bookField(metadata, value)
            return value
        }
        val title = field("name", seed.title)
        if (prefix != "ruleBookInfo" && title.isBlank()) return seed.copy(title = title, state = context.book)
        val author = field("author", seed.author)
        val preserveNames = prefix == "ruleBookInfo" && rules.string("canReName").isBlank()
        val finalTitle = if (preserveNames && priorTitle.isNotBlank()) priorTitle else title
        val finalAuthor = if (preserveNames && priorAuthor.isNotBlank()) priorAuthor else author
        context.bookField("name", finalTitle); context.bookField("author", finalAuthor)
        val kind = field("kind", seed.tags.joinToString(","))
        val wordCount = field("wordCount", seed.wordCount)
        val latest = field("lastChapter", seed.latestChapter, "latestChapterTitle")
        val intro = field("intro", seed.description)
        val cover = try {
            field("coverUrl", seed.coverUrl).let { if (it.isBlank()) "" else sourceLink(context.baseUrl, it) }
        } catch (failure: SourceContentException) {
            // Legado treats cover extraction as optional; a bad cover must not discard a novel.
            if (failure.code != ContentError.InvalidRule) throw failure
            ""
        }
        context.bookField("coverUrl", cover)
        val time = field("updateTime", seed.updateTime)
        return seed.copy(title = finalTitle, author = finalAuthor, description = intro, coverUrl = cover,
            tags = if (kind.isBlank()) seed.tags else kind.split('\n', ','), wordCount = wordCount,
            latestChapter = latest, updateTime = time, state = context.book)
    }

    private suspend fun directory(initial: BookRecord): BookRecord {
        // A web-novel catalogue may contain tens of thousands of rows. Its budget is
        // independent of a single chapter's pagination and includes all per-row rules.
        val context = evaluation(initial.book, maxRuleCalls = DIRECTORY_MAX_CHAPTERS * 8 + DIRECTORY_MAX_PAGES * 4)
        spec.toc.string("preUpdateJs").takeIf { it.isNotBlank() }?.let { context.script(it, RuleValue.Empty, "ruleToc.preUpdateJs") }
        val rule = spec.toc.string("chapterList")
        if (rule.isBlank()) throw SourceContentException(ContentError.MissingCapability, "ruleToc.chapterList")
        val tocUrl = context.book.metadata["tocUrl"]?.jsonPrimitive?.content ?: initial.book.tocUrl
        val queue = ArrayDeque<String>().apply { add(sourceLink(initial.book.id, tocUrl)) }
        val visited = linkedSetOf<String>()
        val chapters = mutableListOf<RuleChapter>()
        var completePageList = false
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (url in visited) continue
            visit(visited, url, "ruleToc.nextTocUrl", DIRECTORY_MAX_PAGES)
            val document = initial.document?.takeIf { visited.size == 1 && it.url == url }
                ?: fetch(context, url, "ruleToc.chapterList")
            context.baseUrl = document.ruleUrl
            if (!document.inline && document.url != url && !visited.add(document.url)) continue
            val items = context.value(rule.removePrefix("-").removePrefix("+"), document.input(), "ruleToc.chapterList", OutputKind.Elements).items()
            if (items.size + chapters.size > DIRECTORY_MAX_CHAPTERS) throw SourceContentException(ContentError.Limit, "ruleToc.chapterList")
            for ((index, item) in items.withIndex()) {
                val row = context.fork(chapterId = "pending:$index")
                row.chapterField("baseUrl", JsonPrimitive(document.url))
                row.chapterField("bookUrl", JsonPrimitive(initial.book.id))
                val title = row.text(spec.toc.string("chapterName"), item, "ruleToc.chapterName")
                if (title.isBlank()) continue
                row.chapterField("title", JsonPrimitive(title))
                val raw = row.text(spec.toc.string("chapterUrl"), item, "ruleToc.chapterUrl")
                row.chapterField("url", JsonPrimitive(raw))
                val time = row.text(spec.toc.string("updateTime"), item, "ruleToc.updateTime")
                row.chapterField("updateTime", JsonPrimitive(time))
                row.chapterField("tag", JsonPrimitive(time))
                val volume = row.text(spec.toc.string("isVolume"), item, "ruleToc.isVolume").truth()
                val id = if (volume && raw.isBlank()) "volume:" + digest("$url:$index:$title") else try {
                    sourceLink(document.url, raw.ifBlank { url })
                } catch (failure: SourceContentException) {
                    if (failure.code != ContentError.InvalidRule) throw failure
                    // Reading retains locked/placeholder links in the catalogue. Validate them
                    // when requested so one non-HTTP link cannot discard all readable chapters.
                    raw
                }
                row.chapterId = id
                row.chapterField("url", JsonPrimitive(raw.ifBlank { if (volume) title + index else url }))
                row.chapterField("isVolume", JsonPrimitive(volume))
                val vip = row.text(spec.toc.string("isVip"), item, "ruleToc.isVip").truth()
                val pay = row.text(spec.toc.string("isPay"), item, "ruleToc.isPay").truth()
                row.chapterField("isVip", JsonPrimitive(vip)); row.chapterField("isPay", JsonPrimitive(pay))
                chapters += RuleChapter(id, title, volume, vip, pay, time, row.chapter)
                context.book = row.book
            }
            if (!completePageList) {
                val next = links(context, spec.toc.string("nextTocUrl"), document, "ruleToc.nextTocUrl")
                    .filter { it != document.url }
                // Legado expands an initial page list once; a single link follows the next-page chain.
                completePageList = context.page == 1 && next.size > 1
                (if (completePageList) next else next.take(1)).forEach(queue::addLast)
            }
            context.page++
        }
        // The pinned default keeps the last occurrence, then restores the requested source order.
        val reverse = (context.book.metadata["readConfig"] as? JsonObject)?.get("reverseToc")?.jsonPrimitive?.booleanOrNull == true
        val ordered = (if (rule.startsWith('-')) chapters else chapters.reversed()).distinctBy { it.id }
            .let { if (reverse) it else it.reversed() }
        if (ordered.none { !it.isVolume }) throw SourceContentException(ContentError.EmptyContent, "ruleToc.chapterList")
        context.book = context.book.copy(metadata = JsonObject(context.book.metadata + ("totalChapterNum" to JsonPrimitive(ordered.size))))
        val formatted = mutableListOf<RuleChapter>()
        var gInt: JsonElement = JsonPrimitive(0)
        for ((index, chapter) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            val row = context.fork(chapterId = chapter.id); row.chapter = chapter.state
            row.chapterField("index", JsonPrimitive(index))
            val format = spec.toc.string("formatJs")
            val title = if (format.isBlank()) chapter.title else try {
                val value = row.script("""
                    (function(){var index=${index + 1},title=${JsonPrimitive(chapter.title)},gInt=$gInt;
                    var formatted=eval(${JsonPrimitive(scriptBody(format))});
                    return JSON.stringify({title:formatted==null?title:String(formatted),gInt:gInt});})()
                """.trimIndent(), RuleValue.Text(chapter.title), "ruleToc.formatJs").text()
                val state = Json.parseToJsonElement(value).jsonObject
                gInt = state.getValue("gInt")
                state.getValue("title").jsonPrimitive.content
            } catch (failure: SourceContentException) {
                if (failure.code != ContentError.InvalidRule) throw failure
                chapter.title
            }
            row.chapterField("title", JsonPrimitive(title))
            formatted += chapter.copy(title = title, state = row.chapter)
            context.book = row.book
        }
        return initial.copy(book = initial.book.copy(state = context.book), chapters = formatted, document = null)
    }

    private suspend fun record(id: String): BookRecord {
        val old = store.read(id)
        return if (old?.informationLoaded == true && old.revision == identity.revision) old else information(id, old)
    }
    internal fun evaluation(book: RuleBook? = null, chapter: RuleChapter? = null, keyword: String = "", page: Int = 1,
        interactive: Boolean = false, maxRuleCalls: Int = 65536): RuleEvaluation {
        val result = RuleEvaluation(identity, authority, session, runner, spec.library, book?.id, chapter?.id,
            book?.state ?: ScriptState(), chapter?.state ?: ScriptState(), book?.id ?: spec.baseUrl, keyword, page,
            headerRule = spec.header, interactive = interactive, trace = trace, sourceLoginUrl = spec.loginUrl,
            sourceComment = spec.comment, verification = ::verification, maxRuleCalls = maxRuleCalls,
            sourceName = definition.displayName, sourceLastUpdateTime = spec.lastUpdateTime)
        book?.let {
            result.bookField("bookUrl", it.id)
            if ("name" !in result.book.metadata) result.bookField("name", it.title)
            if ("tocUrl" !in result.book.metadata) result.bookField("tocUrl", it.tocUrl)
        }
        chapter?.let {
            if ("url" !in result.chapter.metadata) result.chapterField("url", JsonPrimitive(it.id))
            result.chapterField("title", JsonPrimitive(it.title)); result.chapterField("bookUrl", JsonPrimitive(book!!.id))
        }
        return result
    }
    private suspend fun request(context: RuleEvaluation, url: String, field: String, kind: ResourceKind = ResourceKind.Document,
        browser: BrowserOptions? = null): BrokerResponse = send(context, prepareRequest(context, url, field, kind, browser), field)

    /** Executes a prepared rule request and records the User-Agent its response was fetched with. */
    private suspend fun send(context: RuleEvaluation, request: BrokerRequest, field: String): BrokerResponse {
        val response = executeRequest(request, field)
        context.requestUserAgent = if (response.protocol == "data") null else session.requestUserAgent(response.finalUrl, request.headers)
        return response
    }

    private suspend fun prepareRequest(context: RuleEvaluation, url: String, field: String, kind: ResourceKind,
        browser: BrowserOptions? = null): BrokerRequest {
        val prepared = context.script("host.call('request.prepare',result)[0]", RuleValue.Text(url), field).text()
        val compiled = RequestCompiler().compile("content", prepared, context.baseUrl, context.keyword, context.page, context.headers(), kind)
        val request = when (compiled) {
            is CompiledRequest.Ready -> compiled.request
            is CompiledRequest.Rejected -> throw SourceContentException(if (compiled.code == hnovel.network.FailureCode.BrowserRequired)
                ContentError.BrowserRequired else ContentError.InvalidRule, field)
        }
        return request.copy(browser = browser ?: request.browser)
    }

    private suspend fun executeRequest(request: BrokerRequest, field: String, publicImage: Boolean = false): BrokerResponse {
        val started = System.nanoTime()
        val guard = RequestCommitGuard { authority.authorized(identity, it) }
        val result = if (publicImage) session.loadImage(request, guard)
            else session.execute(if (request.kind == ResourceKind.Image) request.copy(cache = CacheMode.Disabled) else request, guard)
        trace.record(ContentTraceEvent("network", field, (System.nanoTime() - started) / 1_000_000,
            request.body?.length ?: 0, (result as? BrokerResult.Success)?.response?.body?.size ?: 0,
            when (result) { is BrokerResult.Success -> when {
                result.response.kind == ResponseKind.BrowserDocument -> "BrowserDocument"
                result.response.protocol == "data" -> "Inline"
                else -> "HTTP_${result.response.status}"
            }; is BrokerResult.Failure -> result.code.name }))
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        val response = when (result) {
            is BrokerResult.Success -> result.response
            is BrokerResult.Failure -> throw SourceContentException(result.code.contentError(), field, result.denial,
                verification = verification(result))
        }
        if (request.kind == ResourceKind.Image && response.status !in 200..299)
            throw SourceContentException(ContentError.Network, field)
        return response
    }
    private suspend fun fetch(context: RuleEvaluation, url: String, field: String, browser: BrowserOptions? = null,
        acceptErrorResponse: Boolean = false): PageDocument {
        val request = prepareRequest(context, url, field, ResourceKind.Document, browser)
        val response = send(context, request, field)
        val inline = response.protocol == "data"
        context.baseUrl = if (inline) url else response.finalUrl
        if (spec.loginCheck.isBlank()) {
            if (!acceptErrorResponse) checkStatus(response.status, field, response.kind == ResponseKind.BrowserDocument)
            return PageDocument(response.text(), response.finalUrl, inline, context.baseUrl)
        }
        // The pinned hook receives and returns StrResponse, including retry responses from java.connect.
        val snapshot = response.scriptSnapshot(binary = false)
        // Only this hook may explicitly re-execute the request it checks; the refetch does not run the hook again.
        context.currentRequest = request
        val checked = try {
            context.script("""
                result=host.call('response.view',JSON.parse(result));
                result=eval(${JsonPrimitive(scriptBody(spec.loginCheck))});
                JSON.stringify({body:result.getBody(),url:result.getUrl(),status:result.code(),
                    browserDocument:typeof result.isBrowserDocument==='function' && result.isBrowserDocument()});
            """.trimIndent(), RuleValue.Text(snapshot.toString()), "loginCheckJs").text()
        } finally { context.currentRequest = null }
        val value = Json.parseToJsonElement(checked).jsonObject
        if (!acceptErrorResponse) checkStatus(value.getValue("status").jsonPrimitive.int, "loginCheckJs", value["browserDocument"]?.jsonPrimitive?.boolean == true)
        val finalUrl = sourceLink(response.finalUrl, value.getValue("url").jsonPrimitive.content)
        context.baseUrl = if (inline) url else finalUrl
        return PageDocument(value.getValue("body").jsonPrimitive.content, finalUrl, inline, context.baseUrl)
    }

    private fun verification(failure: BrokerResult.Failure): SourceVerification? {
        val certificate = failure.certificate
        if (failure.code == hnovel.network.FailureCode.Certificate && certificate != null) {
            return SourceVerification(null, certificate) {
                operation("certificate.verification") {
                    authority.authorized(identity) { session.approveCertificate(certificate) }
                }
            }
        }
        val kind = failure.challenge ?: return null
        val request = failure.verificationRequest ?: return null
        if (failure.code != hnovel.network.FailureCode.BrowserRequired) return null
        return SourceVerification(kind) {
            operation("browser.verification", timeoutMillis = 300000) {
                // Recovery opens immediately and finishes when the original extraction is ready.
                // Ordinary account/login windows keep their explicit completion behavior.
                val options = request.browser ?: BrowserOptions()
                val result = session.execute(request.copy(browser = options.copy(interactive = true,
                    title = definition.displayName, script = options.script.ifBlank { "document.documentElement.outerHTML" })),
                    RequestCommitGuard { authority.authorized(identity, it) })
                if (result is BrokerResult.Failure)
                    throw SourceContentException(result.code.contentError(), "browser.verification", result.denial,
                        verification = if (result.certificate != null) verification(result) else null)
                if (result is BrokerResult.Success)
                    checkStatus(result.response.status, "browser.verification", result.response.kind == ResponseKind.BrowserDocument)
                // A newer failed request must retain its own fallback target.
                authority.authorized(identity) {
                    // Completing a website flow preserves a session, not a universal authentication verdict.
                    check(session.write(StorageRequest(StorageArea.Account, "login/status", "session")) is StorageResult.Value)
                    val key = StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)
                    if ((session.read(key) as? StorageResult.Value)?.value == request.url) session.write(key)
                }
            }
        }
    }

    private fun checkStatus(status: Int, field: String, browserDocument: Boolean = false) {
        if (browserDocument && status == 0) return
        // A public page can return 401/403 for a WAF or other access policy. Only an
        // explicit login request establishes an authentication failure for this account.
        if (status == 401 && (field == "loginUrl" || field.startsWith("loginUi."))) {
            authority.authorized(identity) { session.write(StorageRequest(StorageArea.Account, "login/status", "required")) }
            throw SourceContentException(ContentError.LoginRequired, field)
        }
        if (status !in 200..299) throw SourceContentException(ContentError.Network, field)
    }
    private suspend fun links(context: RuleEvaluation, rule: String, document: PageDocument, field: String): List<String> =
        if (rule.isBlank()) emptyList() else context.value(rule, document.input(), field, OutputKind.TextList).items()
            .map { it.text().trim() }.filter { it.isNotEmpty() }.map { sourceLink(document.url, it) }.distinct()
    private fun visit(visited: MutableSet<String>, url: String, field: String, maxPages: Int = 64) {
        if (!visited.add(url)) throw SourceContentException(ContentError.RepeatedPage, field)
        if (visited.size > maxPages) throw SourceContentException(ContentError.Limit, field)
    }
    // Host storage and orchestration use IO. The caller's priority dispatcher owns the outer
    // request only; nested timeout jobs must not compete with their parent for its last permit.
    internal suspend fun <T : Any> operation(field: String, timeoutMillis: Long = 60000, block: suspend () -> T): T = withContext(Dispatchers.IO) { serial.withLock {
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        try { withTimeoutOrNull(timeoutMillis) { block().also { currentCoroutineContext().ensureActive()
            if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field) } }
            ?: throw SourceContentException(ContentError.Limit, field) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SourceContentException) { throw failure }
        catch (_: Exception) { throw SourceContentException(if (!authority.accepts(identity)) ContentError.Unavailable else ContentError.InvalidRule, field) }
    } }
    override fun close() { authority.revoke(identity) }
}

private fun PageDocument.input() = RuleValue.Text(body)
private fun String.isScriptRule() = trimStart().let { it.startsWith("@js:", true) || it.startsWith("<js>", true) }
private fun String.truth() = isNotBlank() && this != "null" && trim().lowercase() !in setOf("false", "no", "not", "0", "0.0")
