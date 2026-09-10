package hnovel.content

import hnovel.execution.*
import hnovel.imports.SourceDefinition
import hnovel.network.*
import hnovel.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One registered revision/account. Source retirement revokes its ticket; it never chooses another source. */
class RuleSource(val definition: SourceDefinition, private val identity: ExecutionIdentity,
    private val authority: ExecutionAuthority, private val session: SourceSession,
    private val runner: RuleTaskRunner) : AutoCloseable {
    private val spec = RuleSourceDefinition(definition)
    private val store = RuleBookStore(session, authority, identity)
    private val serial = Mutex()
    val canSearch get() = spec.searchUrl.isNotBlank()
    init {
        require(identity.sourceId == definition.sourceId && identity.profile == definition.profile && identity.revision == definition.contentDigest)
        require(session.scope.sourceId == identity.sourceId && session.scope.namespace == identity.namespace &&
            session.scope.profile == identity.profile && session.scope.accountGeneration == identity.accountGeneration)
    }

    suspend fun search(keyword: String, page: Int = 1): List<RuleBook> = operation("ruleSearch") {
        if (!canSearch) throw SourceContentException(ContentError.MissingCapability, "searchUrl")
        val context = evaluation(keyword = keyword, page = page)
        val document = fetch(context, spec.searchUrl, "searchUrl")
        val rule = spec.search.string("bookList")
        val isBookUrl = spec.bookUrlPattern.isNotBlank() && context.value(":\\A(?:${spec.bookUrlPattern})\\z",
            RuleValue.Text(document.url), "bookUrlPattern", OutputKind.Elements).items().isNotEmpty()
        if (rule.isBlank() || isBookUrl) {
            val id = sourceLink(document.url, document.url)
            val record = information(id, BookRecord(identity.revision, RuleBook(id, state = context.book)), document)
            store.write(record)
            return@operation listOf(record.book)
        }
        val items = context.value(rule.removePrefix("-").removePrefix("+"), document.input(), "ruleSearch.bookList", OutputKind.Elements).items()
        if (items.size > 1000) throw SourceContentException(ContentError.Limit, "ruleSearch.bookList")
        val books = mutableListOf<RuleBook>()
        for (item in items) {
            val row = context.fork()
            val parsed = bookFields(row, item, spec.search, "ruleSearch", RuleBook(""))
            if (parsed.title.isBlank()) continue
            val rawUrl = row.text(spec.search.string("bookUrl"), item, "ruleSearch.bookUrl")
            val id = sourceLink(document.url, rawUrl.ifBlank { document.url })
            row.bookId = id; row.bookField("bookUrl", id)
            books += parsed.copy(id = id, tocUrl = id, state = row.book)
        }
        val ordered = (if (rule.startsWith('-')) books.reversed() else books).distinctBy { it.id }
        ordered.forEach { book ->
            val old = store.read(book.id)
            if (old?.informationLoaded != true || old.revision != identity.revision)
                store.write(BookRecord(identity.revision, book))
        }
        ordered
    }

    suspend fun information(bookId: String): RuleBook = operation("ruleBookInfo") {
        val id = sourceLink(spec.baseUrl, bookId)
        val old = store.read(id)
        var refreshed = information(id, old)
        // Sources without an update marker still participate in host background update checks.
        if (refreshed.book.latestChapter.isBlank() && refreshed.book.updateTime.isBlank()) {
            refreshed = directory(refreshed)
            if (old?.chapters?.map { it.id } != refreshed.chapters.map { it.id })
                refreshed = refreshed.copy(book = refreshed.book.copy(observedUpdate = System.currentTimeMillis()))
        }
        store.write(refreshed)
        refreshed.book
    }

    suspend fun directory(bookId: String): List<RuleChapter> = operation("ruleToc") {
        val id = sourceLink(spec.baseUrl, bookId)
        directory(record(id)).also(store::write).chapters
    }

    suspend fun content(bookId: String, chapterId: String): RuleContent = operation("ruleContent") {
        val id = sourceLink(spec.baseUrl, bookId)
        var record = record(id)
        if (record.chapters.isEmpty()) record = directory(record)
        val index = record.chapters.indexOfFirst { it.id == chapterId && !it.isVolume }
        if (index < 0) throw SourceContentException(ContentError.Unavailable, "chapter")
        val chapter = record.chapters[index]
        val previous = record.chapters.take(index).lastOrNull { !it.isVolume }?.id
        val next = record.chapters.drop(index + 1).firstOrNull { !it.isVolume }?.id
        val context = evaluation(record.book, chapter)
        if (spec.content.string("webJs").isNotBlank() || spec.content.string("sourceRegex").isNotBlank())
            throw SourceContentException(ContentError.BrowserRequired, "ruleContent.webJs")
        val rule = spec.content.string("content")
        if (rule.isBlank()) throw SourceContentException(ContentError.MissingCapability, "ruleContent.content")
        val queue = ArrayDeque<String>().apply { add(chapter.id) }
        val visited = linkedSetOf<String>()
        val pages = mutableListOf<String>()
        var title = chapter.title
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (url == next) continue // A next-chapter link never changes this logical chapter's content or identity.
            visit(visited, url, "ruleContent.nextContentUrl")
            val document = fetch(context, url, "ruleContent.content")
            // Redirects cannot turn a continuation (or the first page) into the next logical chapter.
            if (document.url == next) continue
            if (document.url != url && !visited.add(document.url)) throw SourceContentException(ContentError.RepeatedPage, "ruleContent.nextContentUrl")
            if (pages.isEmpty()) context.text(spec.content.string("title"), document.input(), "ruleContent.title")
                .takeIf { it.isNotBlank() }?.let { title = it; context.chapterField("title", JsonPrimitive(it)) }
            val html = context.text(rule, document.input(), "ruleContent.content", unescape = false)
            // Resolve image links against each response, before joining pages and applying whole-chapter replacements.
            val normalized = context.script("""
                (function(){var body=java.getElements('@css:body').first();body.select('img[src]').forEach(function(img){
                    img.attr('src',img.absUrl('src'))});return body.html()})()
            """.trimIndent(), RuleValue.Text(html), "ruleContent.images").text()
            pages += normalized
            if (pages.sumOf { it.length.toLong() } > 512000) throw SourceContentException(ContentError.Limit, "ruleContent.content")
            val following = links(context, spec.content.string("nextContentUrl"), document, "ruleContent.nextContentUrl")
            following.filter { it != next }.forEach(queue::addLast)
            context.page++
        }
        if (pages.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleContent.content")
        var merged = pages.joinToString("\n")
        val replacement = spec.content.string("replaceRegex")
        if (replacement.isNotBlank()) merged = context.text(replacement, RuleValue.Text(merged), "ruleContent.replaceRegex", unescape = false)
        val parts = context.script(contentMarkupScript, RuleValue.Text(merged), "ruleContent.parts").items().map {
            Json.decodeFromString(ContentPart.serializer(), it.text())
        }.map { part -> if (part.image != null) part.copy(image = sourceLink(context.baseUrl, part.image)) else part }
        if (parts.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleContent.content")
        title = context.chapter.metadata["title"]?.jsonPrimitive?.content ?: title
        val updated = chapter.copy(title = title, state = context.chapter)
        store.write(record.copy(book = record.book.copy(state = context.book),
            chapters = record.chapters.toMutableList().apply { set(index, updated) }))
        RuleContent(chapter.id, title, parts, previous, next)
    }

    suspend fun image(bookId: String, url: String, cover: Boolean): ByteArray = operation("image") {
        val id = sourceLink(spec.baseUrl, bookId)
        val book = store.read(id)?.book ?: RuleBook(id)
        val context = evaluation(book)
        val response = request(context, sourceLink(id, url), "image", ResourceKind.Image)
        val decode = if (cover) spec.coverDecode else spec.content.string("imageDecode")
        if (decode.isBlank()) return@operation response.body
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

    private suspend fun information(id: String, old: BookRecord?, supplied: PageDocument? = null): BookRecord {
        val initial = old?.book ?: RuleBook(id)
        val context = evaluation(initial)
        val document = supplied ?: fetch(context, id, "ruleBookInfo")
        context.baseUrl = document.url
        val init = spec.information.string("init")
        val input = if (init.isBlank()) document.input() else context.value(init, document.input(), "ruleBookInfo.init", OutputKind.Element)
        var book = bookFields(context, input, spec.information, "ruleBookInfo", initial)
        if (book.title.isBlank()) throw SourceContentException(ContentError.EmptyContent, "ruleBookInfo.name")
        val toc = context.text(spec.information.string("tocUrl"), input, "ruleBookInfo.tocUrl")
        val tocUrl = sourceLink(document.url, toc.ifBlank { id })
        context.bookField("tocUrl", tocUrl)
        val changed = old?.informationLoaded != true || initial.latestChapter != book.latestChapter || initial.updateTime != book.updateTime
        book = book.copy(tocUrl = tocUrl, state = context.book,
            observedUpdate = if (changed) System.currentTimeMillis() else initial.observedUpdate)
        return BookRecord(identity.revision, book, true, document, old?.chapters.orEmpty())
    }

    private suspend fun bookFields(context: RuleEvaluation, input: RuleValue, rules: JsonObject, prefix: String, seed: RuleBook): RuleBook {
        val priorTitle = context.book.metadata["name"]?.jsonPrimitive?.content ?: seed.title
        val priorAuthor = context.book.metadata["author"]?.jsonPrimitive?.content ?: seed.author
        suspend fun field(name: String, prior: String, metadata: String = name): String {
            val value = context.text(rules.string(name), input, "$prefix.$name").ifBlank {
                context.book.metadata[metadata]?.jsonPrimitive?.content ?: prior
            }
            context.bookField(metadata, value)
            return value
        }
        val title = field("name", seed.title); val author = field("author", seed.author)
        val preserveNames = prefix == "ruleBookInfo" && rules.string("canReName").isBlank()
        val finalTitle = if (preserveNames && priorTitle.isNotBlank()) priorTitle else title
        val finalAuthor = if (preserveNames && priorAuthor.isNotBlank()) priorAuthor else author
        context.bookField("name", finalTitle); context.bookField("author", finalAuthor)
        val kind = context.text(rules.string("kind"), input, "$prefix.kind")
        context.bookField("kind", kind)
        val wordCount = field("wordCount", seed.wordCount)
        val latest = field("lastChapter", seed.latestChapter, "latestChapterTitle")
        val intro = field("intro", seed.description)
        val cover = field("coverUrl", seed.coverUrl).let { if (it.isBlank()) "" else sourceLink(context.baseUrl, it) }
        context.bookField("coverUrl", cover)
        val time = field("updateTime", seed.updateTime)
        return seed.copy(title = finalTitle, author = finalAuthor, description = intro, coverUrl = cover,
            tags = if (kind.isBlank()) seed.tags else kind.split('\n', ','), wordCount = wordCount,
            latestChapter = latest, updateTime = time, state = context.book)
    }

    private suspend fun directory(initial: BookRecord): BookRecord {
        val context = evaluation(initial.book)
        spec.toc.string("preUpdateJs").takeIf { it.isNotBlank() }?.let { context.script(it, RuleValue.Empty, "ruleToc.preUpdateJs") }
        val rule = spec.toc.string("chapterList")
        if (rule.isBlank()) throw SourceContentException(ContentError.MissingCapability, "ruleToc.chapterList")
        val tocUrl = context.book.metadata["tocUrl"]?.jsonPrimitive?.content ?: initial.book.tocUrl
        val queue = ArrayDeque<String>().apply { add(sourceLink(initial.book.id, tocUrl)) }
        val visited = linkedSetOf<String>()
        val chapters = mutableListOf<RuleChapter>()
        val pageChapters = mutableSetOf<List<String>>()
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst(); visit(visited, url, "ruleToc.nextTocUrl")
            val document = initial.document?.takeIf { visited.size == 1 && it.url == url }
                ?: fetch(context, url, "ruleToc.chapterList")
            context.baseUrl = document.url
            if (document.url != url && !visited.add(document.url)) throw SourceContentException(ContentError.RepeatedPage, "ruleToc.nextTocUrl")
            val items = context.value(rule.removePrefix("-").removePrefix("+"), document.input(), "ruleToc.chapterList", OutputKind.Elements).items()
            if (items.size + chapters.size > 5000) throw SourceContentException(ContentError.Limit, "ruleToc.chapterList")
            val pageStart = chapters.size
            for ((index, item) in items.withIndex()) {
                val row = context.fork(chapterId = "pending:$index")
                val title = row.text(spec.toc.string("chapterName"), item, "ruleToc.chapterName")
                if (title.isBlank()) continue
                row.chapterField("title", JsonPrimitive(title))
                val raw = row.text(spec.toc.string("chapterUrl"), item, "ruleToc.chapterUrl")
                val volume = row.text(spec.toc.string("isVolume"), item, "ruleToc.isVolume").truth()
                val id = if (volume && raw.isBlank()) "volume:" + digest("$url:$index:$title") else sourceLink(document.url, raw.ifBlank { url })
                row.chapterId = id; row.chapterField("url", JsonPrimitive(id)); row.chapterField("isVolume", JsonPrimitive(volume))
                val time = row.text(spec.toc.string("updateTime"), item, "ruleToc.updateTime")
                val vip = row.text(spec.toc.string("isVip"), item, "ruleToc.isVip").truth()
                val pay = row.text(spec.toc.string("isPay"), item, "ruleToc.isPay").truth()
                row.chapterField("isVip", JsonPrimitive(vip)); row.chapterField("isPay", JsonPrimitive(pay))
                row.chapterField("updateTime", JsonPrimitive(time))
                chapters += RuleChapter(id, title, volume, vip, pay, time, row.chapter)
                context.book = row.book
            }
            val ids = chapters.drop(pageStart).filterNot { it.isVolume }.map { it.id }
            if (ids.isNotEmpty() && !pageChapters.add(ids)) throw SourceContentException(ContentError.RepeatedPage, "ruleToc.nextTocUrl")
            links(context, spec.toc.string("nextTocUrl"), document, "ruleToc.nextTocUrl").forEach(queue::addLast)
            context.page++
        }
        if (chapters.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleToc.chapterList")
        // The pinned default keeps the last occurrence, then restores the requested source order.
        val ordered = (if (rule.startsWith('-')) chapters else chapters.reversed()).distinctBy { it.id }.reversed()
        context.book = context.book.copy(metadata = JsonObject(context.book.metadata + ("totalChapterNum" to JsonPrimitive(ordered.size))))
        val formatted = mutableListOf<RuleChapter>()
        var gInt: JsonElement = JsonPrimitive(0)
        for ((index, chapter) in ordered.withIndex()) {
            val row = context.fork(chapterId = chapter.id); row.chapter = chapter.state
            row.chapterField("index", JsonPrimitive(index))
            val format = spec.toc.string("formatJs")
            val title = if (format.isBlank()) chapter.title else {
                val value = row.script("""
                    (function(){var index=${index + 1},title=${JsonPrimitive(chapter.title)},gInt=$gInt;
                    var formatted=eval(${JsonPrimitive(scriptBody(format))});
                    return JSON.stringify({title:formatted==null?title:String(formatted),gInt:gInt});})()
                """.trimIndent(), RuleValue.Text(chapter.title), "ruleToc.formatJs").text()
                val state = Json.parseToJsonElement(value).jsonObject
                gInt = state.getValue("gInt")
                state.getValue("title").jsonPrimitive.content
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
    private fun evaluation(book: RuleBook? = null, chapter: RuleChapter? = null, keyword: String = "", page: Int = 1): RuleEvaluation {
        val result = RuleEvaluation(identity, authority, session, runner, spec.library, book?.id, chapter?.id,
            book?.state ?: ScriptState(), chapter?.state ?: ScriptState(), book?.id ?: spec.baseUrl, keyword, page)
        book?.let {
            result.bookField("bookUrl", it.id)
            if ("name" !in result.book.metadata) result.bookField("name", it.title)
            if ("tocUrl" !in result.book.metadata) result.bookField("tocUrl", it.tocUrl)
        }
        chapter?.let { result.chapterField("url", JsonPrimitive(it.id)); result.chapterField("title", JsonPrimitive(it.title)); result.chapterField("bookUrl", JsonPrimitive(book!!.id)) }
        return result
    }
    private suspend fun headers(context: RuleEvaluation): Map<String, String> {
        if (spec.header.isBlank()) return emptyMap()
        val value = if (spec.header.trimStart().startsWith('{')) Json.parseToJsonElement(spec.header)
            else Json.parseToJsonElement(context.script(spec.header, RuleValue.Empty, "header").text())
        return value.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
    private suspend fun request(context: RuleEvaluation, url: String, field: String, kind: ResourceKind = ResourceKind.Document): BrokerResponse {
        val prepared = context.script("host.call('request.prepare',result)[0]", RuleValue.Text(url), field).text()
        val compiled = RequestCompiler().compile("content", prepared, context.baseUrl, context.keyword, context.page, headers(context), kind)
        val request = when (compiled) {
            is CompiledRequest.Ready -> compiled.request
            is CompiledRequest.Rejected -> throw SourceContentException(if (compiled.code == hnovel.network.FailureCode.BrowserRequired)
                ContentError.BrowserRequired else ContentError.InvalidRule, field)
        }
        val result = session.execute(request, RequestCommitGuard { authority.authorized(identity, it) })
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        val response = when (result) {
            is BrokerResult.Success -> result.response
            is BrokerResult.Failure -> throw SourceContentException(when (result.code) {
                hnovel.network.FailureCode.OriginDenied, hnovel.network.FailureCode.AddressDenied -> ContentError.PermissionDenied
                hnovel.network.FailureCode.ResponseTooLarge, hnovel.network.FailureCode.Timeout -> ContentError.Limit
                else -> ContentError.Network
            }, field)
        }
        if (kind == ResourceKind.Image) checkStatus(response.status, field)
        return response
    }
    private suspend fun fetch(context: RuleEvaluation, url: String, field: String): PageDocument {
        val response = request(context, url, field)
        context.baseUrl = response.finalUrl
        if (spec.loginCheck.isBlank()) {
            checkStatus(response.status, field)
            return PageDocument(response.text(), response.finalUrl)
        }
        // The pinned hook receives and returns StrResponse, including retry responses from java.connect.
        val snapshot = response.scriptSnapshot(binary = false)
        val checked = context.script("""
            result=host.call('response.view',JSON.parse(result));
            result=eval(${JsonPrimitive(scriptBody(spec.loginCheck))});
            JSON.stringify({body:result.getBody(),url:result.getUrl(),status:result.code()});
        """.trimIndent(), RuleValue.Text(snapshot.toString()), "loginCheckJs").text()
        val value = Json.parseToJsonElement(checked).jsonObject
        checkStatus(value.getValue("status").jsonPrimitive.int, "loginCheckJs")
        val finalUrl = sourceLink(response.finalUrl, value.getValue("url").jsonPrimitive.content)
        context.baseUrl = finalUrl
        return PageDocument(value.getValue("body").jsonPrimitive.content, finalUrl)
    }
    private fun checkStatus(status: Int, field: String) {
        if (status == 401 || status == 403) throw SourceContentException(ContentError.LoginRequired, field)
        if (status !in 200..299) throw SourceContentException(ContentError.Network, field)
    }
    private suspend fun links(context: RuleEvaluation, rule: String, document: PageDocument, field: String): List<String> =
        if (rule.isBlank()) emptyList() else context.value(rule, document.input(), field, OutputKind.TextList).items()
            .map { it.text().trim() }.filter { it.isNotEmpty() }.map { sourceLink(document.url, it) }.distinct()
    private fun visit(visited: MutableSet<String>, url: String, field: String) {
        if (!visited.add(url)) throw SourceContentException(ContentError.RepeatedPage, field)
        if (visited.size > 64) throw SourceContentException(ContentError.Limit, field)
    }
    // Host storage and orchestration use IO. The caller's priority dispatcher owns the outer
    // request only; nested timeout jobs must not compete with their parent for its last permit.
    private suspend fun <T : Any> operation(field: String, block: suspend () -> T): T = withContext(Dispatchers.IO) { serial.withLock {
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        try { withTimeoutOrNull(60000) { block().also { currentCoroutineContext().ensureActive()
            if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field) } }
            ?: throw SourceContentException(ContentError.Limit, field) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SourceContentException) { throw failure }
        catch (_: Exception) { throw SourceContentException(if (!authority.accepts(identity)) ContentError.Unavailable else ContentError.InvalidRule, field) }
    } }
    override fun close() { authority.revoke(identity) }
}

private fun PageDocument.input() = RuleValue.Text(body)
private fun String.truth() = isNotBlank() && this != "false" && this != "0"
