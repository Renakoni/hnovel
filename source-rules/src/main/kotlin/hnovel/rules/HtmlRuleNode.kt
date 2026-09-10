package hnovel.rules

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.parser.Tag

/** Reconstruct the selected element, including table-row parsing context, rather than wrapping it as a document. */
fun RuleValue.Node.htmlElement(baseUrl: String = ""): Element {
    require(kind == InputKind.Html)
    if (parentTag == "#root") return Jsoup.parse(content, baseUrl).child(0)
    val context = Element(Tag.valueOf(parentTag ?: "body"), baseUrl)
    return Parser.htmlParser().parseFragmentInput(content, context, baseUrl).filterIsInstance<Element>().singleOrNull()
        ?: Jsoup.parse(content, baseUrl).body()
}
