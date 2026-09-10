package hnovel.rhino

import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.BufferedInputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlinx.serialization.json.*

/** Complete Connection.Response data contract; this object cannot execute a request. */
internal class ResponseSnapshot(private var location: URL, private var verb: Connection.Method,
    private val status: Int, private val statusText: String, private var encoding: String?,
    private val content: ByteArray, initialHeaders: Map<String, List<String>>) : Connection.Response {
    private val fields = linkedMapOf<String, MutableList<String>>().apply { initialHeaders.forEach { (k, v) -> put(k, v.toMutableList()) } }
    private val jar = linkedMapOf<String, String>().apply {
        initialHeaders.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach { cookie ->
            val pair = cookie.substringBefore(';'); val split = pair.indexOf('=')
            if (split > 0) putIfAbsent(pair.substring(0, split).trim(), pair.substring(split + 1).trim())
        }
    }
    private val type = header("Content-Type")
    private enum class BodyState { Fresh, Buffered, Parsed, Stream }
    private var state = BodyState.Fresh
    fun checkSize(limit: Int) {
        if (content.size > limit) throw ResultTooLarge()
        BoundedJsonResult(limit - content.size).encodeJson(buildJsonObject {
            put("url", location.toString()); put("method", verb.name)
            put("status", status); put("message", statusText)
            put("charset", encoding); put("contentType", type)
            put("headers", ScriptData.json(fields)); put("cookies", ScriptData.json(jar))
        })
    }
    private fun key(name: String) = fields.keys.firstOrNull { it.equals(name, true) }
    override fun statusCode() = status
    override fun statusMessage() = statusText
    override fun charset() = encoding
    override fun charset(charset: String?) = apply { encoding = charset }
    override fun contentType() = type
    override fun url() = location
    override fun url(url: URL) = apply { location = url }
    override fun method() = verb
    override fun method(method: Connection.Method) = apply { verb = method }
    override fun header(name: String): String? = headers(name).takeIf { it.isNotEmpty() }?.joinToString(", ")
    // Pinned Jsoup exposes existing values live, but returns an immutable empty list for a missing header.
    override fun headers(name: String): MutableList<String> = key(name)?.let { fields.getValue(it) } ?: java.util.Collections.emptyList()
    override fun header(name: String, value: String) = apply { require(name.isNotEmpty()); removeHeader(name); addHeader(name, value) }
    override fun addHeader(name: String, value: String) = apply {
        require(name.isNotEmpty())
        val values = headers(name)
        if (values.isEmpty()) fields[name] = mutableListOf(value) else values.add(value)
    }
    override fun hasHeader(name: String) = headers(name).isNotEmpty()
    override fun hasHeaderWithValue(name: String, value: String) = headers(name).any { it.equals(value, true) }
    override fun removeHeader(name: String) = apply { key(name)?.let(fields::remove) }
    override fun headers(): MutableMap<String, String> = fields.filterValues { it.isNotEmpty() }.mapValuesTo(linkedMapOf()) { it.value.first() }
    override fun multiHeaders(): MutableMap<String, MutableList<String>> = fields
    override fun cookie(name: String): String? = jar[name]
    override fun cookie(name: String, value: String) = apply { require(name.isNotEmpty()); jar[name] = value }
    override fun hasCookie(name: String) = name in jar
    override fun removeCookie(name: String) = apply { jar.remove(name) }
    override fun cookies(): MutableMap<String, String> = jar
    // Pinned Jsoup makes bufferUp a no-op after parse closed an unbuffered body;
    // it does not restore bytes. Buffered content supports body/bytes/parse, not bodyStream.
    override fun bufferUp() = apply {
        check(state != BodyState.Stream)
        if (state == BodyState.Fresh) state = BodyState.Buffered
    }
    override fun bodyAsBytes(): ByteArray { bufferUp(); check(state == BodyState.Buffered); return content.copyOf() }
    override fun body(): String { bufferUp(); check(state == BodyState.Buffered); return Charset.forName(encoding ?: "UTF-8").decode(ByteBuffer.wrap(content)).toString() }
    override fun bodyStream(): BufferedInputStream { check(state == BodyState.Fresh); state = BodyState.Stream; return content.inputStream().buffered() }
    override fun parse(): Document {
        check(state == BodyState.Fresh || state == BodyState.Buffered)
        val parser = if (type?.contains("xml", true) == true) Parser.xmlParser() else Parser.htmlParser()
        return content.inputStream().use { Jsoup.parse(it, encoding, location.toExternalForm(), parser) }.also {
            if (state == BodyState.Fresh) state = BodyState.Parsed
            encoding = it.outputSettings().charset().name()
        }
    }
}
