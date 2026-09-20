package io.nightfish.potatoepub.builder

import org.dom4j.Document

class Chapter(
    val title: String,
    val chapterContent: Document?,
    val chapters: List<Chapter>?
) {
    var id: String = "chapter"
        internal set
    constructor(title: String, chapterContent: Document): this(title, chapterContent, chapters = null)
    constructor(title: String, chapters: List<Chapter>): this(title, null, chapters)
}
