package com.orator.feature.player

import com.orator.core.database.ChapterEntity
import com.orator.core.playback.PlaybackConnection.QueueItemSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRowsTest {

    private fun ch(i: Int, startMs: Long) = ChapterEntity(
        bookId = "b", chapterIndex = i,
        title = "Ch $i", fileUri = "u", startMs = startMs, durationMs = 0,
    )

    @Test
    fun `multi-item queue maps snapshot rows with current flag`() {
        val rows = QueueRows.build(
            currentIndex = 1,
            positionMs = 0,
            snapshot = listOf(
                QueueItemSnapshot(0, "audiobook/b/0", "File 0"),
                QueueItemSnapshot(1, "audiobook/b/1", "File 1"),
            ),
            chapters = emptyList(),
        )
        assertEquals(2, rows.size)
        assertTrue(rows[1].isCurrent)
        assertEquals(PlayerChapters.SeekTarget(0, 0), rows[0].seekTarget)
    }

    @Test
    fun `single-item book with chapters expands to chapter rows`() {
        val rows = QueueRows.build(
            currentIndex = 0,
            positionMs = 70_000,
            snapshot = listOf(QueueItemSnapshot(0, "audiobook/b/0", "Dracula")),
            chapters = listOf(ch(0, 0), ch(1, 60_000), ch(2, 150_000)),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[1].isCurrent) // position 70s is inside chapter 1
        assertEquals(PlayerChapters.SeekTarget(0, 150_000), rows[2].seekTarget)
    }

    @Test
    fun `single episode stays one row`() {
        val rows = QueueRows.build(
            0, 0,
            listOf(QueueItemSnapshot(0, "podcast/e1", "Ep 214")),
            emptyList(),
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0].isCurrent)
    }
}
