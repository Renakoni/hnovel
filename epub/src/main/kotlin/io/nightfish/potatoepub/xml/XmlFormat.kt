package io.nightfish.potatoepub.xml

import org.dom4j.Document
import org.dom4j.DocumentHelper
import org.dom4j.io.OutputFormat
import org.dom4j.io.XMLWriter
import java.io.StringWriter


fun Document.asFormatedXml(): String {
    val format = OutputFormat()
    format.encoding = "UTF-8"
    // Indenting mixed XHTML content inserts whitespace into the book's text.
    format.isNewlines = false
    format.isExpandEmptyElements = false
    val strWtr = StringWriter()
    val xmlWrt = XMLWriter(strWtr, format)
    xmlWrt.write(DocumentHelper.parseText(sanitizeXmlSimple(this.asXML())))
    xmlWrt.flush()
    xmlWrt.close()
    return strWtr.toString()
        .replaceFirst(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        )
}

fun sanitizeXmlSimple(xml: String): String =
    xml
        .replace(Regex("&#(?:x[0-9a-fA-F]+|\\d+);")) { m ->
            val s = m.value
            val cp = if (s.startsWith("&#x", ignoreCase = true)) {
                s.substring(3, s.length - 1).toIntOrNull(16)
            } else {
                s.substring(2, s.length - 1).toIntOrNull()
            }
            if (cp != null && isXmlCharacter(cp)) m.value else ""
        }
        .let { value ->
            buildString(value.length) {
                var index = 0
                while (index < value.length) {
                    val codePoint = value.codePointAt(index)
                    if (isXmlCharacter(codePoint)) appendCodePoint(codePoint)
                    index += Character.charCount(codePoint)
                }
            }
        }

private fun isXmlCharacter(codePoint: Int): Boolean =
    codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
        codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD ||
        codePoint in 0x10000..0x10FFFF