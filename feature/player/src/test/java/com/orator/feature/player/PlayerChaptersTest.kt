package com.orator.feature.player

import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerChaptersTest {

    // M4B: 3 chapters at 0 / 60s / 150s in a 300s book.
    private val m4b = listOf(
        ch(0, startMs = 0), ch(1, startMs = 60_000), ch(2, startMs = 150_000),
    )

    // MP3_DIR: 3 files of 60s / 90s / 150s.
    private val mp3 = listOf(
        ch(0, durationMs = 60_000), ch(1, durationMs = 90_000), ch(2, durationMs = 150_000),
    )

    private fun ch(i: Int, startMs: Long = 0, durationMs: Long = 0) = ChapterEntity(
        bookId = "b", chapterIndex = i, title = "Ch $i", fileUri = "u$i",
        startMs = startMs, durationMs = durationMs,
    )

    @Test
    fun `m4b current chapter from position`() {
        val c = PlayerChapters.current(
            m4b, SourceKind.SINGLE_FILE, currentIndex = 0,
            positionMs = 70_000, totalDurationMs = 300_000,
        )!!
        assertEquals(1, c.index)
        assertEquals(3, c.count)
        assertEquals(10_000, c.positionInChapterMs)
        assertEquals(90_000, c.chapterDurationMs) // 150s − 60s
    }

    @Test
    fun `m4b last chapter duration runs to book end`() {
        val c = PlayerChapters.current(m4b, SourceKind.SINGLE_FILE, 0, 200_000, 300_000)!!
        assertEquals(2, c.index)
        assertEquals(150_000, c.chapterDurationMs)
    }

    @Test
    fun `mp3 current chapter is the queue index`() {
        val c = PlayerChapters.current(
            mp3, SourceKind.MULTI_FILE, currentIndex = 1,
            positionMs = 30_000, totalDurationMs = 300_000,
        )!!
        assertEquals(1, c.index)
        assertEquals(30_000, c.positionInChapterMs)
        assertEquals(90_000, c.chapterDurationMs)
    }

    @Test
    fun `empty chapters yields null`() {
        assertNull(PlayerChapters.current(emptyList(), SourceKind.SINGLE_FILE, 0, 0, 100))
    }

    @Test
    fun `next and previous targets m4b`() {
        assertEquals(
            PlayerChapters.SeekTarget(0, 150_000),
            PlayerChapters.next(m4b, SourceKind.SINGLE_FILE, 0, 70_000),
        )
        // >3s into chapter 1 → prev goes to its start
        assertEquals(
            PlayerChapters.SeekTarget(0, 60_000),
            PlayerChapters.previous(m4b, SourceKind.SINGLE_FILE, 0, 70_000),
        )
        // <3s into chapter 1 → prev goes to chapter 0
        assertEquals(
            PlayerChapters.SeekTarget(0, 0),
            PlayerChapters.previous(m4b, SourceKind.SINGLE_FILE, 0, 61_000),
        )
        // next at the last chapter: null (nothing to do)
        assertNull(PlayerChapters.next(m4b, SourceKind.SINGLE_FILE, 0, 200_000))
    }

    @Test
    fun `next and previous targets mp3`() {
        assertEquals(
            PlayerChapters.SeekTarget(2, 0),
            PlayerChapters.next(mp3, SourceKind.MULTI_FILE, 1, 30_000),
        )
        assertEquals(
            PlayerChapters.SeekTarget(1, 0),
            PlayerChapters.previous(mp3, SourceKind.MULTI_FILE, 1, 30_000),
        )
        assertEquals(
            PlayerChapters.SeekTarget(0, 0),
            PlayerChapters.previous(mp3, SourceKind.MULTI_FILE, 1, 1_000),
        )
    }

    @Test
    fun `chapter tap targets`() {
        assertEquals(
            PlayerChapters.SeekTarget(0, 60_000),
            PlayerChapters.tap(m4b, SourceKind.SINGLE_FILE, 1),
        )
        assertEquals(
            PlayerChapters.SeekTarget(1, 0),
            PlayerChapters.tap(mp3, SourceKind.MULTI_FILE, 1),
        )
    }

    @Test
    fun `whole-item fraction and ticks`() {
        assertEquals(0.5f, PlayerChapters.itemFraction(mp3, SourceKind.MULTI_FILE, 1, 90_000, 300_000), 0.001f)
        assertEquals(0.5f, PlayerChapters.itemFraction(m4b, SourceKind.SINGLE_FILE, 0, 150_000, 300_000), 0.001f)
        assertEquals(listOf(0.2f, 0.5f), PlayerChapters.ticks(m4b, SourceKind.SINGLE_FILE, 300_000))
        assertEquals(listOf(0.2f, 0.5f), PlayerChapters.ticks(mp3, SourceKind.MULTI_FILE, 300_000))
    }

    @Test
    fun `item seek fraction to target`() {
        assertEquals(
            PlayerChapters.SeekTarget(0, 150_000),
            PlayerChapters.itemSeek(m4b, SourceKind.SINGLE_FILE, 0.5f, 300_000),
        )
        assertEquals(
            PlayerChapters.SeekTarget(1, 30_000),
            PlayerChapters.itemSeek(mp3, SourceKind.MULTI_FILE, 0.3f, 300_000),
        )
    }
}
