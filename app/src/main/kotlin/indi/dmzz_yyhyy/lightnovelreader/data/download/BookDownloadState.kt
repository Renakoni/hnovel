package indi.dmzz_yyhyy.lightnovelreader.data.download

import indi.dmzz_yyhyy.lightnovelreader.data.book.BookIdentity
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import java.security.MessageDigest

enum class BookDownloadPhase { None, Partial, Complete, Updating, Failed, Outdated }

data class BookDownloadState(val phase: BookDownloadPhase = BookDownloadPhase.None,
    val savedChapters: Int = 0, val totalChapters: Int = 0)

internal fun downloadDirectoryHash(volumes: BookVolumes): String = downloadHash(
    BookIdentity.encode("download-directory", volumes.volumes.flatMap { volume ->
        listOf(volume.volumeId, volume.volumeTitle) + volume.chapters.flatMap { listOf(it.id, it.title) }
    }))

internal fun downloadChapterSignature(chapters: List<ChapterInformation>, index: Int, revision: String): String =
    downloadHash(BookIdentity.encode("download-chapter", listOf(revision, chapters[index].id, chapters[index].title,
        chapters.getOrNull(index - 1)?.id.orEmpty(), chapters.getOrNull(index + 1)?.id.orEmpty())))

internal fun downloadHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
