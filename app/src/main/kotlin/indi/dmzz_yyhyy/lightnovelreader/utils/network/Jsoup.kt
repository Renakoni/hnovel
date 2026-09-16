package indi.dmzz_yyhyy.lightnovelreader.utils.network

import org.jsoup.nodes.Document

fun Document.selectFirstXpath(path: String) =
    this.selectXpath(path).firstOrNull()