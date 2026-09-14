package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkerRuleTest {
    @Test fun jsonListsRemainStructuredAcrossScriptAndSelectorStages() {
        val input = RuleValue.Text("""{"chapterlist":[{"chapterid":1},{"chapterid":2}]}""")
        val task = ExecutionTask.Rule("""$.chapterlist[*]||$.[*]<js>result</js>$.[*]""",
            input, OutputKind.Elements)
        val rows = (value(run(task)).value as RuleValue.Items).values
        assertEquals(listOf(1, 2), rows.map { Json.parseToJsonElement((it as RuleValue.Node).content)
            .jsonObject.getValue("chapterid").jsonPrimitive.int })
        val mixed = task.copy(rule = """<js>[7,true,null,"line\ntext",[2,3],{id:4}]</js>$.[*]<js>JSON.stringify(result)</js>""",
            output = OutputKind.Elements)
        assertEquals(RuleValue.Text("""[7,true,null,"line\ntext",[2,3],{"id":4}]"""), value(run(mixed)).value)
        assertEquals(emptyList<RuleValue>(), (value(run(task.copy(input = RuleValue.Text("""{"chapterlist":[]}"""),
            rule = """$.chapterlist[*]<js>result</js>$.[*]"""))).value as RuleValue.Items).values)
    }
    @Test fun singleJsonObjectsDoNotAcquireAnArrayWrapperBeforeLaterStages() {
        val task = ExecutionTask.Rule("""$.data<js>result.book_id</js>""",
            RuleValue.Text("""{"data":{"book_id":"9007199254740993101"}}"""), OutputKind.Element)
        assertEquals(RuleValue.Text("9007199254740993101"), value(run(task)).value)
    }
    @Test fun metadataAndScopedWritesCrossWireWithoutChangingBookIdentity() {
        val book = buildJsonObject { put("name", "Same book"); put("author", "Same author"); put("bookUrl", "/book"); put("wordCount", "10k"); put("kind", "novel,fiction") }
        val chapter = buildJsonObject { put("url", "one,{\"method\":\"POST\"}"); put("baseUrl", "https://fixture.invalid/toc/"); put("title", "One") }
        val task = ExecutionTask.Rule("""@js:
            book.putVariable('token','book');chapter.putVariable('token',null);
            [book.name,book.getKindList().join('|'),chapter.getAbsoluteURL(),java.get('token'),book.id].join(';')
        """, RuleValue.Text("input"), OutputKind.Text, bookId="source-a:book", book=book, chapter=chapter,
            chapterVariables=mapOf("token" to "old"))
        val result = value(run(task))
        assertEquals(RuleValue.Text("Same book;10k|novel|fiction;https://fixture.invalid/toc/one,{\"method\":\"POST\"};book;source-a:book"), result.value)
        assertEquals(mapOf("token" to "book"), result.bookWrites)
        assertEquals(mapOf("token" to null), result.chapterWrites)
        assertEquals("Same book", book.getValue("name").jsonPrimitive.content)
        assertEquals(RuleValue.Text(""), value(run(task.copy(rule="@js:book.getVariable('token')", chapterVariables=emptyMap()))).value)
    }
    private val id = ExecutionAuthority().issue("a", "legado", "1")
    private fun run(task: ExecutionTask.Rule): ExecutionResult {
        val input = ExecutionWire.encode(id, task, ExecutionLimits())
        return ExecutionWire.decodeResult(WorkerMain.executeSerialized(input.toString(Charsets.UTF_8)).toByteArray())
    }
    private fun value(result: ExecutionResult): ExecutedRule {
        assertTrue(result.toString(), result is ExecutionResult.Success)
        return Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
    }

    @Test fun htmlSelectionFeedsRhinoAndJsonObjectResultsFeedLaterRules() {
        val input = RuleValue.Text("<ul><li>one</li><li>two</li></ul>")
        val output = value(run(ExecutionTask.Rule("tag.li@text<js>result.map(function(x){return x.toUpperCase()})</js>", input)))
        assertEquals(RuleValue.Items(listOf(RuleValue.Text("ONE"), RuleValue.Text("TWO"))), output.value)
        assertEquals(RuleValue.Text("ONE"), value(run(ExecutionTask.Rule(
            "<js>({title:'ONE'})</js>$.title", input, OutputKind.Text))).value)
    }

    @Test fun whitespaceAroundScriptsPreservesChapterUrlsAndPaginationArrays() {
        val base = "https://fixture.invalid/ta/listAjax.m?nid=1&order=0&page=1&size=100"
        val row = RuleValue.Node("""{"sort":2,"chapterName":"Two"}""", InputKind.Json)
        val rule = """<js>
            url=baseUrl.replace(/listAjax/g,"show").replace(/&order=\d*&page=\d*&size=\d*/g,"&st={{$.sort}}&gst=0&cu=&cName={{$.chapterName}}");
            u=String(url)+",{'webView': true}"
            </js>
        """.trimIndent() + "\r\n"
        assertEquals(RuleValue.Text("https://fixture.invalid/ta/show.m?nid=1&st=2&gst=0&cu=&cName=Two,{'webView': true}"),
            value(run(ExecutionTask.Rule(rule, row, OutputKind.Text, baseUrl = base))).value)
        assertEquals(RuleValue.Items(listOf(RuleValue.Text(base), RuleValue.Text(base.replace("page=1", "page=2")))),
            value(run(ExecutionTask.Rule(" \n<js>[baseUrl,baseUrl.replace(/page=\\d+/,'page=2')]</js>\r\n",
                row, OutputKind.TextList, baseUrl = base))).value)
        for (ruleWithSpace in listOf(" \n<js>result+'!'</js> \n<js>result+'?'</js>\r\n", "\t@js:result+'!?'")) {
            assertEquals(RuleValue.Text("chapter!?"), value(run(ExecutionTask.Rule(ruleWithSpace,
                RuleValue.Text("chapter"), OutputKind.Text))).value)
        }
    }

    @Test fun javaVariablesShareRuleContextButDoNotLeakIntoAnotherBook() {
        val task = ExecutionTask.Rule("<js>java.put('title',java.get('title')+'!');result</js>@get:{title}", RuleValue.Text("input"),
            OutputKind.Text, sourceVariables = mapOf("title" to "source"), bookVariables = mapOf("title" to "book"),
            chapterVariables = mapOf("title" to "chapter"))
        assertEquals(ExecutedRule(RuleValue.Text("chapter!"), mapOf("title" to "chapter!")), value(run(task)))
        assertEquals(ExecutedRule(RuleValue.Text("source"), emptyMap()), value(run(task.copy(rule = "@get:{title}",
            bookVariables = emptyMap(), chapterVariables = emptyMap()))))
    }

    @Test fun scriptTemplatesReadTheOriginalDocumentBeforeContentDecoding() {
        val html = RuleValue.Text("<body data-order='normal'><article>chapter</article></body>")
        val rule = """article@text<js>var order="{{@@body@data-order}}";order+':'+result.toUpperCase()</js>"""
        assertEquals(RuleValue.Text("normal:CHAPTER"), value(run(ExecutionTask.Rule(rule, html, OutputKind.Text))).value)
        assertEquals(RuleValue.Text("normal"), value(run(ExecutionTask.Rule(
            """@js:"{{@@body@data-order}}"""", html, OutputKind.Text))).value)
    }

    @Test fun scalarEntitiesAreDecodedAfterTheLastScript() {
        // Observed with the same input/rules in the reference app's AnalyzeRule.getString.
        val input = RuleValue.Text("<h1>&amp;amp;</h1>")
        assertEquals(RuleValue.Text("&"), value(run(ExecutionTask.Rule("@js:'&amp;'", input, OutputKind.Text))).value)
        assertEquals(RuleValue.Text("raw"), value(run(ExecutionTask.Rule(
            "h1@text<js>result === '&amp;' ? 'raw' : 'decoded'</js>", input, OutputKind.Text))).value)
        assertEquals(RuleValue.Text("&amp;"), value(run(ExecutionTask.Rule(
            "@js:'&amp;'", input, OutputKind.Text, unescapeHtml = false))).value)
    }

    @Test fun html4DecodingDoesNotCorruptSignedRequestParameters() {
        val request = "https://fixture.invalid/info,{\"method\":\"POST\",\"body\":\"id=1&timestamp=2&notin=3\"}"
        val task = ExecutionTask.Rule("@js:" + JsonPrimitive(request), RuleValue.Text("input"), OutputKind.Text)
        assertEquals(RuleValue.Text(request), value(run(task)).value)
        assertEquals(RuleValue.Text("& &apos; × &times &#65 A"), value(run(task.copy(
            rule = "@js:'&amp; &apos; &times; &times &#65 &#65;'"))).value)
    }

    @Test fun scriptStagesShareAnalyzeRuleContentButKeepResultIndependent() {
        val task = ExecutionTask.Rule("""<js>java.setContent('<p>new</p>');'stage'</js>
            <js>result+'|'+java.getString('p@text')+'|'+src</js>""".trimIndent(),
            RuleValue.Text("<p>old</p>"), OutputKind.Text)
        assertEquals(RuleValue.Text("stage|new|<p>new</p>"), value(run(task)).value)
        assertEquals(RuleValue.Text("<p>old</p>"), value(run(task.copy(rule = "@js:src"))).value)
    }

    @Test fun nestedScriptsBindCurrentContentAndBaseWithoutChangingTheCallingScript() {
        val task = ExecutionTask.Rule("""@js:
            java.setContent('<p>new</p>','https://changed.invalid/dir/');
            baseUrl+'|'+src+'|'+java.getString('@js:baseUrl+"|"+src')
            """.trimIndent(), RuleValue.Text("<p>old</p>"), OutputKind.Text, baseUrl = "https://fixture.invalid/")
        assertEquals(RuleValue.Text("https://fixture.invalid/|<p>old</p>|https://changed.invalid/dir/|<p>new</p>"),
            value(run(task)).value)
    }

    @Test fun scriptGetPlaceholdersExpandFromRuleVariables() {
        val task = ExecutionTask.Rule("<js>'@get:{name}|@get:{missing}'</js>", RuleValue.Empty,
            OutputKind.Text, sourceVariables = mapOf("name" to "chapter"))
        assertEquals(RuleValue.Text("chapter|"), value(run(task)).value)
        assertEquals(RuleValue.Text("updated|"), value(run(task.copy(
            rule = "<js>java.put('name','updated');result</js><js>'@get:{name}|@get:{missing}'</js>"))).value)
    }

    @Test fun selectorTemplatesAndPutsUseCurrentContentDuringExplicitReads() {
        val task = ExecutionTask.Rule("""@js:
            java.getString('@put:{"root":"h1@text"}span@text','<span>explicit</span>');java.get('root')
            """.trimIndent(), RuleValue.Text("<h1>ROOT</h1>"), OutputKind.Text)
        assertEquals(RuleValue.Text("ROOT"), value(run(task)).value)
        assertEquals(RuleValue.Text("NEW"), value(run(task.copy(
            rule = "<js>java.setContent('<h1>NEW</h1>');'value'</js>{{@@h1@text}}"))).value)
    }

    @Test fun selectorListsCanBeCopiedToArraysBeforeScriptConcatenation() {
        val task = ExecutionTask.Rule("""$.items[*]<js>
            var copy=result.toArray();copy.push({id:3});
            [{length:result.length,ids:copy.map(x=>x.id),keys:Object.keys(result)}]
            </js>""".trimIndent(), RuleValue.Text("""{"items":[{"id":1},{"id":2}]}"""), OutputKind.Elements)
        val row = (value(run(task)).value as RuleValue.Items).values.single() as RuleValue.Node
        assertEquals(Json.parseToJsonElement("""{"length":2,"ids":[1,2,3],"keys":["0","1"]}"""),
            Json.parseToJsonElement(row.content))
    }

    @Test fun scriptStringListsSplitBeforeUrlResolution() {
        val task = ExecutionTask.Rule("@js:'one\\ntwo'", RuleValue.Text("input"), OutputKind.TextList,
            baseUrl = "https://fixture.invalid/toc/")
        assertEquals(RuleValue.Items(listOf(RuleValue.Text("one"), RuleValue.Text("two"))), value(run(task)).value)
        assertEquals(RuleValue.Items(listOf(RuleValue.Text("https://fixture.invalid/toc/one"),
            RuleValue.Text("https://fixture.invalid/toc/two"))), value(run(task.copy(output = OutputKind.UrlList))).value)
    }

    @Test fun completeCataloguePayloadFitsBinderAndExpansionRemainsBounded() {
        val chapters = JsonArray((0 until 1600).map { index -> buildJsonObject {
            put("id", index); put("title", "Chapter $index"); put("metadata", "x".repeat(512))
        } })
        val task = ExecutionTask.Rule("$[*]", RuleValue.Text(chapters.toString()), OutputKind.Elements)
        val limits = ExecutionLimits(maxOutputBytes = 2 * 1024 * 1024)
        val raw = ExecutionWire.encode(id, task, limits)
        assertTrue(raw.size > ExecutionWire.MAX_INPUT_PACKET_BYTES)
        val request = checkNotNull(ExecutionPayload.pack(raw, ExecutionWire.MAX_INPUT_PACKET_BYTES))
        val reply = WorkerMain.executeSerialized(ExecutionPayload.unpack(request, ExecutionWire.MAX_INPUT_BYTES).toString(Charsets.UTF_8)).toByteArray()
        assertTrue(reply.size > BridgeWire.MAX_BYTES)
        val packet = checkNotNull(ExecutionPayload.pack(reply, BridgeWire.MAX_BYTES))
        val result = value(ExecutionWire.decodeResult(ExecutionPayload.unpack(packet, ExecutionWire.MAX_RESULT_BYTES)))
        val rows = (result.value as RuleValue.Items).values
        assertEquals(1600, rows.size)
        assertEquals(1599, Json.parseToJsonElement((rows.last() as RuleValue.Node).content).jsonObject.getValue("id").jsonPrimitive.int)
        assertThrows(IllegalArgumentException::class.java) { ExecutionPayload.unpack(request, raw.size - 1) }
    }

    @Test fun scriptCatalogueTransformsHundredsOfChaptersWithinTheWorkerDeadline() {
        val chapters = JsonArray((1..600).map { index -> buildJsonObject {
            put("sort", index); put("chapter_name", "Chapter $index"); put("wordCount", 3000)
        } })
        val input = buildJsonObject { put("volumes", JsonArray(listOf(buildJsonObject {
            put("name", "Volume"); put("chapters", chapters)
        }))) }
        val task = ExecutionTask.Rule("""<js>
            let obj={showjname:false}, data=JSON.parse(String(result)), array=[];
            data.volumes.forEach(booklet=>{
                java.put('jname',booklet.name);
                array.push({name:booklet.name,voltype:true});
                booklet.chapters.forEach(chapter=>{
                    let href='https://fixture.invalid/chapter?gid='+java.get('gid')+'&sort='+chapter.sort;
                    array.push({name:!java.get('jname')?chapter.chapter_name:
                        ((obj.showjname?'['+java.get('jname')+'] ':'').padStart(3,''))+chapter.chapter_name,
                        url:href,time:'Words:'+String(chapter.wordCount),voltype:false});
                });
            });
            array
            </js>""".trimIndent(), RuleValue.Text(input.toString()), OutputKind.Elements,
            sourceVariables = mapOf("gid" to "1"))
        val wire = ExecutionWire.encode(id, task, ExecutionLimits(maxOutputBytes = 2 * 1024 * 1024))
        val result = value(ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8)).toByteArray()))
        val rows = (result.value as RuleValue.Items).values
        assertEquals(601, rows.size)
        val last = Json.parseToJsonElement((rows.last() as RuleValue.Node).content).jsonObject
        assertEquals("Chapter 600", last.getValue("name").jsonPrimitive.content)
        assertEquals("https://fixture.invalid/chapter?gid=1&sort=600", last.getValue("url").jsonPrimitive.content)
    }

    @Test fun scriptErrorsKeepStageFieldAndOffsetWithoutExposingCode() {
        val rule = "tag.h1@text<js>return 'secret'</js>"
        val failure = run(ExecutionTask.Rule(rule, RuleValue.Text("<h1>A</h1>"), location = RuleLocation("ruleBookInfo.name", 7)))
        assertEquals(ExecutionResult.Failure(FailureCode.ScriptSyntax,
            RuleError(RuleStage.Script, RuleLocation("ruleBookInfo.name", 7 + rule.indexOf("<js>") + 4), "Syntax")), failure)
        assertFalse(failure.toString().contains("secret"))
    }

    @Test(timeout = 15000) fun realChildWorkerRunsJsonAndXPathRulesWithRhino() {
        val worker = IsolatedExecutor()
        val task = ExecutionTask.Rule("$.items[*].name@js:result.map(function(x){return x+'!'})",
            RuleValue.Node("{\"items\":[{\"name\":\"A\"}]}", InputKind.Json))
        assertEquals(RuleValue.Items(listOf(RuleValue.Text("A!"))), value(worker.execute(id, task)).value)
        assertEquals(RuleValue.Text("A"), value(worker.execute(id, ExecutionTask.Rule("//book/text()",
            RuleValue.Node("<root><book>A</book></root>", InputKind.Xml), OutputKind.Text))).value)
    }
}
