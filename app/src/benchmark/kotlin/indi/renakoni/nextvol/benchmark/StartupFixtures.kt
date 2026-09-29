package indi.renakoni.nextvol.benchmark

import android.content.Context
import android.net.Uri
import com.github.michaelbull.result.get
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import java.io.File
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ten ordinary-sized definitions, never a live-site or thousand-source stress fixture. */
internal suspend fun seedStartupSources(sources: ImportedRuleSources) {
    repeat(10) { index ->
        val origin = "https://startup-$index.invalid/"
        val raw = buildJsonObject {
            put("bookSourceUrl", origin)
            put("bookSourceName", "Startup source $index")
            put("bookSourceType", 0)
            put("enabledExplore", false)
            put("ruleContent", buildJsonObject { put("content", "body@text") })
        }
        val preview = sources.importer.preview(raw.toString())
        check(preview.issues.isEmpty())
        check(sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error == null)
        val definition = sources.definitions.list().single { it.importKey == origin }
        sources.activate(definition.reference(), listOf(NetworkGrant(origin)))
    }
}

/** Uses the actual staging, parsing, publication and reading-history paths. */
internal suspend fun seedStartupLocalBook(context: Context, database: NextVolDatabase,
    books: LocalBookStore, format: String, shelfId: Int) {
    val title = "Startup ${format.uppercase()} Fixture"
    val text = (1..30).joinToString("\n\n") { "Startup paragraph $it. Local content for repeatable reading measurements." }
    val file = File(context.cacheDir, "startup.$format")
    if (format == "txt") file.writeText("第一章 开始\n\n$text") else {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, value: String, stored: Boolean = false) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(name).also {
                    if (stored) {
                        it.method = ZipEntry.STORED
                        it.size = bytes.size.toLong()
                        it.crc = CRC32().apply { update(bytes) }.value
                    }
                })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip", stored = true)
            entry("META-INF/container.xml", """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent())
            entry("OPS/package.opf", """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">urn:startup-fixture</dc:identifier><dc:title>$title</dc:title><dc:language>en</dc:language>
                  </metadata>
                  <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent())
            entry("OPS/chapter.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Startup Chapter</title></head>
                  <body><h1>Startup Chapter</h1>${text.split("\n\n").joinToString("") { "<p>$it</p>" }}</body>
                </html>
            """.trimIndent())
            entry("OPS/nav.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>Contents</title></head><body><nav epub:type="toc"><ol>
                    <li><a href="chapter.xhtml">Startup Chapter</a></li>
                  </ol></nav></body>
                </html>
            """.trimIndent())
        }
    }
    val draft = books.stage(Uri.fromFile(file))
    val parsed = books.preview(draft)
    val (book, _) = books.publish(draft, parsed, title, shelfId)
    val chapter = requireNotNull(books.readVolumes(book).get()).volumes.first().chapters.first()
    database.userReadingDataDao().insert(UserReadingDataEntity(book.storageKey, LocalDateTime.now(), 0, 0f,
        chapter.id, chapter.title, emptyMap(), emptyMap()))
    database.userDataDao().insert(UserDataEntity("reading_books", "", "StringList", book.storageKey))
}
