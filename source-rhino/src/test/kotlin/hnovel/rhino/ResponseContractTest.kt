package hnovel.rhino

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ResponseContractTest {
    private fun data(bytes: ByteArray, type: String? = null) = buildJsonObject {
        put("body", bytes.toString(Charsets.UTF_8)); put("bytes", Base64.getEncoder().encodeToString(bytes))
        put("url", "https://fixture.invalid/book"); put("status", 200); put("message", "OK")
        put("method", "POST"); put("protocol", "h2"); put("sentAt", 123); put("receivedAt", 456)
        put("headers", buildJsonObject {
            put("X-Test", JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))))
            if (type != null) put("Content-Type", JsonArray(listOf(JsonPrimitive(type))))
        })
    }
    private fun run(script: String, data: JsonObject): ScriptResult = RhinoScriptEngine(HostBridge { _, _ -> data })
        .evaluate(script, ScriptFrame("a", "legado"))

    @Test fun originalBytesStreamsAndConsumedRawResponseAreDataOnly() {
        val data = data(byteArrayOf(0, 127, -128, -1))
        assertEquals(ScriptResult.Success("[[0,127,-128,-1],0,3,2,[9,127,-128,9],127,2,-1,true,\"undefined\"]"), run("""
            var bytes=java.get('url',{}).bodyAsBytes();var s=java.get('url',{}).bodyStream();
            var first=s.read(),available=s.available();s.mark(4);var a=[9,9,9,9];var n=s.read(a,1,2);
            s.reset();var again=s.read(),skipped=s.skip(9),end=s.read();s.close();
            var closed=false;try{s.read()}catch(e){closed=true}
            [bytes,first,available,n,a,again,skipped,end,closed,typeof s.getClass]
        """, data))
        assertEquals(ScriptResult.Success("[\"POST\",\"h2\",123,456,\"two\",true,\"undefined\",\"undefined\"]"), run("""
            var r=java.connect('url').raw();var consumed=false;try{r.body().bytes()}catch(e){consumed=true}
            [r.request().method(),r.protocol().toString(),r.sentRequestAtMillis(),r.receivedResponseAtMillis(),
             r.headers().get('X-Test'),consumed,typeof r.newBuilder,typeof r.request().url().openConnection]
        """, data))
    }

    @Test fun headerCookieMutationsAndBufferConsumptionMatchPinnedJsoup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<p>one</p>").addHeader("X-Test", "one").addHeader("X-Test", "two"))
            val reference = Jsoup.connect(server.url("/book").toString()).execute()
            reference.header("X-Test", "three").addHeader("x-test", "four").cookie("c", "v")
            reference.multiHeaders()["X-Test"]!!.add("five")
            reference.cookies()["c"] = "changed"
            val expected = listOf(reference.header("x-test"), reference.headers("X-Test").joinToString("|"),
                reference.cookie("c"), reference.bufferUp().parse().select("p").text(), reference.body())
            val result = run("""
                var r=java.get('url',{});r.header('X-Test','three').addHeader('x-test','four').cookie('c','v');
                r.multiHeaders().get('X-Test').add('five');r.cookies().put('c','changed');
                [r.header('x-test'),r.headers('X-Test').join('|'),r.cookie('c'),r.bufferUp().parse().select('p').text(),r.body()]
            """, data("<p>one</p>".toByteArray()))
            assertEquals(ScriptResult.Success(JsonArray(expected.map { JsonPrimitive(it) }).toString()), result)
            assertEquals(ScriptResult.Success("[true,true,true]"), run("""
                var a=java.get('u',{}),b=java.get('u',{}),c=java.get('u',{});a.parse();b.bodyStream();c.body();
                function fails(f){try{f();return false}catch(e){return true}}
                [fails(function(){a.body()}),fails(function(){b.parse()}),fails(function(){c.bodyStream()})]
            """, data("<p>one</p>".toByteArray())))
        }
    }

    @Test fun parseDetectsMetaCharsetWithoutReencodingBinarySnapshot() {
        val bytes = "<meta charset=windows-1252><p>caf\u00e9</p>".toByteArray(charset("windows-1252"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(bytes)))
            val reference = Jsoup.connect(server.url("/").toString()).execute()
            val before = reference.charset(); val text = reference.parse().select("p").text(); val after = reference.charset()
            assertEquals(ScriptResult.Success(buildJsonArray { add(before?.let(::JsonPrimitive) ?: JsonNull); add(text); add(after) }.toString()),
                run("var r=java.get('u',{}),before=r.charset(),text=r.parse().select('p').text();[before,text,r.charset()]", data(bytes,"text/html")))
        }
    }

    @Test fun responseConsumptionSequencesMatchPinnedJsoupIncludingBufferedStreamRejection() {
        val operations = listOf("bufferUp", "body", "bodyAsBytes", "parse", "bodyStream")
        val sequences = operations.flatMap { first -> operations.flatMap { second ->
            operations.map { third -> listOf(first, second, third) }
        } }
        MockWebServer().use { server ->
            for (sequence in sequences) {
                server.enqueue(MockResponse().setBody("<p>one</p>"))
                val reference = Jsoup.connect(server.url("/book").toString()).execute()
                val expected = sequence.map { operation -> runCatching {
                    when (operation) {
                        "bufferUp" -> reference.bufferUp()
                        "body" -> reference.body()
                        "bodyAsBytes" -> reference.bodyAsBytes()
                        "parse" -> reference.parse()
                        else -> reference.bodyStream().use { it.read() }
                    }
                }.isSuccess }
                val calls = JsonArray(sequence.map(::JsonPrimitive))
                assertEquals(sequence.toString(), ScriptResult.Success(JsonArray(expected.map(::JsonPrimitive)).toString()),
                    run("""
                        var r=java.get('u',{});
                        $calls.map(function(operation){
                            try{var value=r[operation]();if(operation==='bodyStream'){value.read();value.close()}return true}
                            catch(e){return false}
                        })
                    """, data("<p>one</p>".toByteArray())))
                if (sequence.take(2) == listOf("bufferUp", "bodyStream")) assertEquals(listOf(true, false), expected.take(2))
            }
        }
    }
}
