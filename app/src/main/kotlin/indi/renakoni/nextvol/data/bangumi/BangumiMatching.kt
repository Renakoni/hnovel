package indi.renakoni.nextvol.data.bangumi

import indi.renakoni.nextvol.data.book.SourceBookId
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.identifier.Identifier
import java.text.Normalizer
import java.util.Locale

object BangumiMatching {
    fun supports(book: SourceBookId) = book.sourceId == Identifier("lightnovelreader", "Wenku8")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it in "！!？?。、，,・·「」『』“”\"" }

    fun candidate(book: BookInformation, subject: BangumiSubject, people: List<BangumiPerson>): BangumiCandidate {
        val names = (subject.values("别名") + subject.name + subject.nameCn).filter(String::isNotBlank).map(::normalize)
        val localNames = listOf(book.title, book.subtitle).filter(String::isNotBlank).map(::normalize)
        // An adaptation's original creator is deliberately not treated as its novel author.
        val authors = (subject.values("作者") + people.filter { it.relation == "作者" }.map { it.name })
            .filter(String::isNotBlank).map(::normalize)
        val author = normalize(book.author)
        val publishers = (subject.values("出版社") + subject.values("书系") +
            people.filter { it.relation in setOf("出版社", "书系") }.map { it.name }).map(::normalize)
        return BangumiCandidate(subject, localNames.any { it in names }, author.isNotEmpty() && author in authors,
            author.isNotEmpty() && authors.isNotEmpty() && author !in authors,
            book.publishingHouse.isNotBlank() && normalize(book.publishingHouse) in publishers)
    }

    /** Extract volume labels, never a catalog position or an unrelated number in a title. */
    fun localNumber(title: String): String? {
        val text = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
        val label = Regex("^第([0-9一二三四五六七八九十百零〇两兩.]+)卷(?:$|\\s|[:：])").find(text)?.groupValues?.get(1)
            ?: return null
        return number(label)
    }

    internal fun remoteNumber(subject: BangumiRelatedSubject): String? =
        sequenceOf(subject.nameCn, subject.name).mapNotNull { title ->
            val text = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
            Regex("(?:[\\s。(（]|^)([0-9]+(?:\\.[0-9]+)?)[)）]?$", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.let(::number)
        }.firstOrNull()

    private fun number(text: String): String? {
        text.toBigDecimalOrNull()?.let { return it.takeIf { n -> n.signum() > 0 }?.stripTrailingZeros()?.toPlainString() }
        val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '兩' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '零' to 0, '〇' to 0)
        var total = 0
        var digit = 0
        for (char in text) when (char) {
            '十', '百' -> { total += (if (digit == 0) 1 else digit) * (if (char == '十') 10 else 100); digit = 0 }
            in digits -> digit = digits.getValue(char)
            else -> return null
        }
        return (total + digit).takeIf { it > 0 }?.toString()
    }

    fun requiredChapters(volume: Volume): Set<String> = volume.chapters
        .filterNot { normalize(it.title) in setOf("插图", "插圖", "后记", "後記") }.mapTo(linkedSetOf()) { it.id }

    fun propose(book: BookInformation, volumes: List<Volume>, subject: BangumiSubject,
        related: List<BangumiRelatedSubject>): List<BangumiVolumeMapping> {
        val editions = related.filter { it.type == 1 && it.relation == "单行本" }
        val numbers = editions.mapNotNull { edition -> remoteNumber(edition)?.let { it to edition.id } }.groupBy({ it.first }, { it.second })
        val countIncludesSpecials = subject.volumes > 0 && editions.size == subject.volumes && numbers.size == editions.size
        val numbered = volumes.mapNotNull { localNumber(it.volumeTitle)?.toBigDecimalOrNull() }
        val latest = numbered.maxOrNull()
        return volumes.map { volume ->
            val label = localNumber(volume.volumeTitle)
            val matches = numbers[label].orEmpty()
            val isMain = label != null && !label.contains('.')
            val key = when {
                label == null -> null
                matches.size == 1 && (isMain || countIncludesSpecials) -> "subject:${matches.single()}"
                matches.isEmpty() && isMain && (subject.volumes == 0 || label.toBigDecimal() <= subject.volumes.toBigDecimal()) -> "volume:$label"
                else -> null
            }
            BangumiVolumeMapping(volume.volumeId, volume.volumeTitle, key, requiredChapters(volume),
                complete = book.isComplete || (label != null && latest != null && label.toBigDecimal() < latest))
        }
    }

    fun completed(mapping: List<BangumiVolumeMapping>, progress: Map<String, Float>): Set<String> = mapping
        .filter { it.editionKey != null }.groupBy { it.editionKey!! }.filterValues { parts ->
            parts.all { part -> part.complete && part.chapterIds.isNotEmpty() && part.chapterIds.all { id ->
                val value = progress[id] ?: 0f
                value.isFinite() && value >= 1f
            } }
        }.keys

    /** Extend known incomplete catalogs; new main volumes additionally need a matching related publication. */
    fun refresh(mapping: List<BangumiVolumeMapping>, volumes: List<Volume>,
        related: List<BangumiRelatedSubject> = emptyList()): List<BangumiVolumeMapping>? {
        if (volumes.isEmpty() || volumes.map { it.volumeId }.distinct().size != volumes.size ||
            volumes.any { it.volumeId.isBlank() || it.chapters.any { chapter -> chapter.id.isBlank() } }) return null
        val byId = volumes.associateBy { it.volumeId }
        val existing = mutableMapOf<String, BangumiVolumeMapping>()
        for (old in mapping) {
            val current = byId[old.volumeId] ?: return null
            if (current.volumeTitle != old.title) return null
            val chapters = requiredChapters(current)
            if (chapters != old.chapterIds && old.editionKey != null &&
                (old.complete || !chapters.containsAll(old.chapterIds))) return null
            existing[old.volumeId] = old.copy(chapterIds = chapters)
        }
        val additions = volumes.filter { it.volumeId !in existing }
        if (additions.isNotEmpty()) {
            var last = mapping.mapNotNull { localNumber(it.title)?.toIntOrNull() }.maxOrNull() ?: return null
            val publications = related.filter { it.type == 1 && it.relation == "单行本" }
                .mapNotNull { item -> remoteNumber(item)?.let { it to item.id } }.groupBy({ it.first }, { it.second })
            for (volume in additions) {
                val number = localNumber(volume.volumeTitle)?.toIntOrNull() ?: return null
                if (number != last + 1) return null
                val remote = publications[number.toString()]?.singleOrNull() ?: return null
                existing[volume.volumeId] = BangumiVolumeMapping(volume.volumeId, volume.volumeTitle,
                    "subject:$remote", requiredChapters(volume), complete = false)
                last = number
            }
        }
        return volumes.map { existing.getValue(it.volumeId) }
    }

    fun catalogMatches(mapping: List<BangumiVolumeMapping>, volumes: List<Volume>): Boolean {
        if (volumes.isEmpty() || volumes.map { it.volumeId }.distinct().size != volumes.size) return false
        if (mapping.map { it.volumeId }.toSet() != volumes.map { it.volumeId }.toSet()) return false
        return mapping.all { row -> volumes.find { it.volumeId == row.volumeId }?.let {
            it.volumeTitle == row.title && requiredChapters(it) == row.chapterIds
        } == true }
    }
}
