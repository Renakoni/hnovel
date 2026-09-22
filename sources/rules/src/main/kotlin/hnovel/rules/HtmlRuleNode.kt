package hnovel.rules

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag

/** Reconstruct the selected element, including table-row parsing context, rather than wrapping it as a document. */
fun RuleValue.Node.htmlElement(baseUrl: String = ""): Element {
    require(kind == InputKind.Html || kind == InputKind.Xml)
    val parser = if (kind == InputKind.Xml) Parser.xmlParser() else Parser.htmlParser()
    if (parentTag == "#root") return Jsoup.parse(content, baseUrl, parser).child(0)
    val context = Element(Tag.valueOf(parentTag ?: "body"), baseUrl)
    return parser.parseFragmentInput(content, context, baseUrl).filterIsInstance<Element>().singleOrNull()
        ?: Jsoup.parse(content, baseUrl, parser).body()
}

/** Preserve an XPath/JSoup element's identity when it crosses a worker or nested-rule boundary. */
fun Element.ruleNode(): RuleValue.Node {
    val xml = ownerDocument()?.outputSettings()?.syntax() == org.jsoup.nodes.Document.OutputSettings.Syntax.xml
    // JSoup attaches clones to a synthetic document, even when the clone is an anchor or row.
    val parentTag = parent()?.tagName()?.takeUnless { it == "#root" && !xml && normalName() != "html" }
        ?: if (xml) "#root" else when (normalName()) {
            "html" -> "#root"
            "td", "th" -> "tr"
            "tr" -> "tbody"
            "tbody", "thead", "tfoot", "caption", "colgroup" -> "table"
            "col" -> "colgroup"
            else -> "body"
        }
    return RuleValue.Node(outerHtml(), if (xml) InputKind.Xml else InputKind.Html, parentTag)
}
