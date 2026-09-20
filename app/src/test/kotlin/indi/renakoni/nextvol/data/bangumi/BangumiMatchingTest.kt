package indi.renakoni.nextvol.data.bangumi

import android.app.Application
import android.net.Uri
import indi.renakoni.nextvol.data.book.SourceBookId
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BangumiMatchingTest {
    private fun book() = BookInformation("book", "春物", "", Uri.EMPTY, "渡航", "", emptyList(), "小学館", WordCount(0), LocalDateTime.MIN, false)
    private fun volume(id: String, title: String) = Volume(id, title, listOf(ChapterInformation("$id-main", "第一章"),
        ChapterInformation("$id-illustration", "插图"), ChapterInformation("$id-afterword", "后记")))

    @Test fun onlyBuiltInWenku8IsSupported() {
        assertTrue(BangumiMatching.supports(SourceBookId(Identifier("lightnovelreader", "Wenku8"), "1")))
        assertFalse(BangumiMatching.supports(SourceBookId(Identifier("other", "Wenku8"), "1")))
    }

    @Test fun parsesVolumeLabelsWithoutBorrowingNumbersFromSpecials() {
        assertEquals("14", BangumiMatching.localNumber("第十四卷"))
        assertEquals("6.5", BangumiMatching.localNumber("第６.５卷"))
        assertNull(BangumiMatching.localNumber("BD特典 高三篇 新1"))
        assertNull(BangumiMatching.localNumber("结2 Yui’s story"))
    }

    @Test fun numberedOregairuVolumesCanIncludeDecimalVolumesWhenThePublicationCountAgrees() {
        val local = listOf(volume("7", "第七卷"), volume("6.5", "第6.5卷"), volume("extra", "BD特典 新1"))
        val related = listOf(BangumiRelatedSubject(7, 1, nameCn = "春物 7", relation = "单行本"),
            BangumiRelatedSubject(65, 1, nameCn = "春物 6.5", relation = "单行本"))
        val result = BangumiMatching.propose(book(), local, BangumiSubject(1, volumes = 2), related)
        assertEquals(listOf("subject:7", "subject:65", null), result.map { it.editionKey })
        assertEquals(setOf("7-main"), result[0].chapterIds)
        assertFalse(result[0].complete)
        assertTrue(result[1].complete)
    }

    @Test fun makeineDecimalVolumeIsNotAutomaticallyCountedWhenItExceedsThePublishedCount() {
        val related = (1..9).map { BangumiRelatedSubject(it, 1, name = "Makeine ($it)", relation = "单行本") } +
            BangumiRelatedSubject(85, 1, name = "Makeine (8.5)", relation = "单行本")
        val result = BangumiMatching.propose(book(), listOf(volume("8.5", "第8.5卷"), volume("9", "第九卷")),
            BangumiSubject(1, volumes = 9), related)
        assertNull(result[0].editionKey)
        assertEquals("subject:9", result[1].editionKey)
        assertFalse(result[1].complete)
    }

    @Test fun progressCountsDistinctCompletedPublicationsRatherThanHighestOrContiguousVolume() {
        val mapping = listOf(BangumiVolumeMapping("1", "第一卷", "one", setOf("1"), true),
            BangumiVolumeMapping("5a", "第五卷上", "five", setOf("5a"), true),
            BangumiVolumeMapping("5b", "第五卷下", "five", setOf("5b"), true))
        assertEquals(setOf("one"), BangumiMatching.completed(mapping, mapOf("1" to 1f, "5a" to 1f)))
        assertEquals(setOf("one", "five"), BangumiMatching.completed(mapping, mapOf("1" to 1f, "5a" to 1f, "5b" to 1f)))
    }

    @Test fun emptyIncompleteAndAlmostFinishedVolumesDoNotCount() {
        val mapping = listOf(BangumiVolumeMapping("empty", "", "empty", emptySet(), true),
            BangumiVolumeMapping("partial", "", "partial", setOf("partial"), false),
            BangumiVolumeMapping("almost", "", "almost", setOf("almost"), true),
            BangumiVolumeMapping("invalid", "", "invalid", setOf("invalid"), true))
        assertTrue(BangumiMatching.completed(mapping, mapOf("partial" to 1f, "almost" to .999f, "invalid" to Float.NaN)).isEmpty())
    }

    @Test fun originalCreatorOnAMangaIsNotASameRoleAuthorMatch() {
        val person = BangumiPerson(1, "渡 航", "原作")
        val subject = BangumiSubject(1, nameCn = "春物", platform = "漫画", series = true)
        assertFalse(subject.isNovel)
        assertFalse(BangumiMatching.candidate(book(), subject, listOf(person)).authorMatches)
        assertTrue(BangumiMatching.candidate(book(), subject.copy(platform = "小说"), listOf(person.copy(relation = "作者"))).authorMatches)
    }

    @Test fun titleAliasesPreserveVersionSuffixesAndCatalogChangesRequireReview() {
        val subject = BangumiSubject(1, name = "春物 新", infobox = buildJsonArray {
            add(buildJsonObject {
                put("key", "别名")
                put("value", buildJsonArray { add(buildJsonObject { put("v", "春物续篇") }) })
            })
        })
        assertFalse(BangumiMatching.candidate(book(), subject, emptyList()).titleMatches)
        val local = volume("one", "第一卷")
        val mapping = listOf(BangumiVolumeMapping("one", "第一卷", "one", BangumiMatching.requiredChapters(local), true))
        assertTrue(BangumiMatching.catalogMatches(mapping, listOf(local)))
        assertFalse(BangumiMatching.catalogMatches(mapping, listOf(local.copy(chapters = local.chapters + ChapterInformation("new", "补章")))))
    }

    @Test fun incompleteCatalogCanGrowButConfirmedCatalogCannotChange() {
        val local = volume("one", "第一卷")
        val row = BangumiVolumeMapping("one", "第一卷", "one", BangumiMatching.requiredChapters(local), false)
        val expanded = local.copy(chapters = local.chapters + ChapterInformation("new", "补章"))
        val refreshed = BangumiMatching.refresh(listOf(row), listOf(expanded))!!
        assertEquals(setOf("one-main", "new"), refreshed.single().chapterIds)
        assertFalse(refreshed.single().complete)
        assertNull(BangumiMatching.refresh(listOf(row.copy(complete = true)), listOf(expanded)))
        assertNull(BangumiMatching.refresh(refreshed, listOf(local)))
    }

    @Test fun addingMainVolumeRequiresAnUnambiguousConsecutivePublication() {
        val one = volume("one", "第一卷")
        val two = volume("two", "第二卷")
        val mapping = listOf(BangumiVolumeMapping("one", "第一卷", "one", BangumiMatching.requiredChapters(one), true))
        val publication = BangumiRelatedSubject(2, 1, name = "Novel (2)", relation = "单行本")
        assertNull(BangumiMatching.refresh(mapping, listOf(one, two)))
        val result = BangumiMatching.refresh(mapping, listOf(one, two), listOf(publication))!!
        assertEquals("subject:2", result.last().editionKey)
        assertFalse(result.last().complete)
        assertNull(BangumiMatching.refresh(mapping, listOf(one, two), listOf(publication, publication.copy(id = 3))))
        assertNull(BangumiMatching.refresh(mapping, listOf(one, volume("extra", "番外")), listOf(publication)))
        assertNull(BangumiMatching.refresh(mapping, listOf(one, volume("three", "第三卷")), listOf(publication)))
    }
}
