package hnovel.rhino

import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.BufferedInputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset

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
    private var read = false
    private var buffered = false
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
    override fun header(name: String): String? = key(name)?.let { fields.getValue(it).joinToString(", ") }
    override fun headers(name: String): MutableList<String> = key(name)?.let { fields.getValue(it).toMutableList() } ?: mutableListOf()
    override fun header(name: String, value: String) = apply { require(name.isNotEmpty()); removeHeader(name); addHeader(name, value) }
    override fun addHeader(name: String, value: String) = apply { require(name.isNotEmpty()); fields.getOrPut(key(name) ?: name) { mutableListOf() }.add(value) }
    override fun hasHeader(name: String) = key(name) != null
    override fun hasHeaderWithValue(name: String, value: String) = headers(name).any { it.equals(value, true) }
    override fun removeHeader(name: String) = apply { key(name)?.let(fields::remove) }
    override fun headers(): MutableMap<String, String> = fields.mapValuesTo(linkedMapOf()) { it.value.first() }
    override fun multiHeaders(): MutableMap<String, MutableList<String>> = fields
    override fun cookie(name: String): String? = jar[name]
    override fun cookie(name: String, value: String) = apply { require(name.isNotEmpty()); jar[name] = value }
    override fun hasCookie(name: String) = name in jar
    override fun removeCookie(name: String) = apply { jar.remove(name) }
    override fun cookies(): MutableMap<String, String> = jar
    override fun bufferUp() = apply { if (!buffered) { check(!read); buffered = true; read = true } }
    override fun bodyAsBytes(): ByteArray { bufferUp(); return content.copyOf() }
    override fun body(): String { bufferUp(); return Charset.forName(encoding ?: "UTF-8").decode(ByteBuffer.wrap(content)).toString() }
    override fun bodyStream(): BufferedInputStream { check(!read); read = true; return content.inputStream().buffered() }
    override fun parse(): Document {
        check(buffered || !read)
        read = true
        val parser = if (type?.contains("xml", true) == true) Parser.xmlParser() else Parser.htmlParser()
        return content.inputStream().use { Jsoup.parse(it, encoding, location.toExternalForm(), parser) }.also {
            encoding = it.outputSettings().charset().name()
        }
    }
}
