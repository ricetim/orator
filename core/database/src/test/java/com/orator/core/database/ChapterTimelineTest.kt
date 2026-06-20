package com.orator.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTimelineTest {
    // 2 files: A=[c0:0..1000, c1:1000..3000], B=[c2:0..1500]
    private fun ch(i: Int, file: String, start: Long, dur: Long) =
        ChapterEntity(bookId = "b", chapterIndex = i, title = "c$i", fileUri = file, startMs = start, durationMs = dur)

    private val chapters = listOf(
        ch(0, "A", 0, 1000), ch(1, "A", 1000, 2000), ch(2, "B", 0, 1500),
    )

    @Test fun files_are_distinct_in_order() {
        assertEquals(listOf("A", "B"), ChapterTimeline.files(chapters))
    }

    @Test fun fileDurations_sum_per_file_in_order() {
        assertEquals(listOf(3000L, 1500L), ChapterTimeline.fileDurations(chapters))
    }

    @Test fun fileIndexOf_chapter() {
        assertEquals(0, ChapterTimeline.fileIndexOf(chapters, 1)) // c1 in file A
        assertEquals(1, ChapterTimeline.fileIndexOf(chapters, 2)) // c2 in file B
    }

    @Test fun globalStartOf_chapter_is_sum_of_preceding_durations() {
        assertEquals(0L, ChapterTimeline.globalStartOf(chapters, 0))
        assertEquals(1000L, ChapterTimeline.globalStartOf(chapters, 1))
        assertEquals(3000L, ChapterTimeline.globalStartOf(chapters, 2))
    }

    @Test fun chapterAtGlobal_finds_containing_chapter() {
        assertEquals(0, ChapterTimeline.chapterAtGlobal(chapters, 500))
        assertEquals(1, ChapterTimeline.chapterAtGlobal(chapters, 2999))
        assertEquals(2, ChapterTimeline.chapterAtGlobal(chapters, 3000))
        assertEquals(2, ChapterTimeline.chapterAtGlobal(chapters, 99999)) // clamp last
    }

    @Test fun empty_is_safe() {
        assertEquals(emptyList<Long>(), ChapterTimeline.fileDurations(emptyList()))
        assertEquals(0, ChapterTimeline.chapterAtGlobal(emptyList(), 100))
    }
}
