package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsDownloadPlanTest {
    private fun ch(idx: Int, uri: String) =
        ChapterEntity("abs:1", idx, "t$idx", uri, 0, 1000)

    @Test fun `distinct remote file uris get stable sequential names`() {
        val chapters = listOf(
            ch(0, "https://s/api/items/1/file/100"),
            ch(1, "https://s/api/items/1/file/100"),
            ch(2, "https://s/api/items/1/file/200"),
        )
        val plan = AbsDownloadPlan.from(sourceUri = "https://s/api/items/1/file/100", chapters = chapters)
        assertEquals(2, plan.files.size)
        assertEquals("track-000", plan.files[0].localName)
        assertEquals("https://s/api/items/1/file/100", plan.files[0].remoteUrl)
        assertEquals("track-001", plan.files[1].localName)
    }

    @Test fun `rewrite maps remote uris to local content uris`() {
        val chapters = listOf(
            ch(0, "https://s/api/items/1/file/100"),
            ch(1, "https://s/api/items/1/file/200"),
        )
        val plan = AbsDownloadPlan.from("https://s/api/items/1/file/100", chapters)
        val local = mapOf(
            "https://s/api/items/1/file/100" to "content://tree/abs-1/track-000",
            "https://s/api/items/1/file/200" to "content://tree/abs-1/track-001",
        )
        val rewrite = plan.rewrite(chapters, "https://s/api/items/1/file/100", local)
        assertEquals("content://tree/abs-1/track-000", rewrite.sourceUri)
        assertEquals("content://tree/abs-1/track-000", rewrite.chapters[0].fileUri)
        assertEquals("content://tree/abs-1/track-001", rewrite.chapters[1].fileUri)
    }
}
