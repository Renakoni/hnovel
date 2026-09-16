package indi.renakoni.nextvol.utils.network

import org.jsoup.nodes.Document

fun Document.selectFirstXpath(path: String) =
    this.selectXpath(path).firstOrNull()