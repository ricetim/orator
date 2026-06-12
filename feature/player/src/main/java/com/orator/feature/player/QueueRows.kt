package com.orator.feature.player

import com.orator.core.database.ChapterEntity
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.playback.PlaybackConnection.QueueItemSnapshot

/** Maps the live Media3 queue into display rows. Read-only — the mixed queue is Phase 5. */
object QueueRows {

    data class Row(
        val title: String,
        val subLine: String,
        val isCurrent: Boolean,
        val seekTarget: PlayerChapters.SeekTarget,
    )

    /**
     * Normal case: one row per queue item. Special case: a single-item queue with chapters
     * (an M4B book) expands to its chapters so the tab isn't a single lonely row.
     */
    fun build(
        currentIndex: Int,
        positionMs: Long,
        snapshot: List<QueueItemSnapshot>,
        chapters: List<ChapterEntity>,
    ): List<Row> {
        if (snapshot.size == 1 && chapters.isNotEmpty()) {
            val sorted = chapters.sortedBy { it.startMs }
            val curIdx = sorted.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
            return sorted.mapIndexed { i, c ->
                Row(
                    title = c.title,
                    subLine = TimeFormats.clock(c.startMs),
                    isCurrent = i == curIdx,
                    seekTarget = PlayerChapters.SeekTarget(0, c.startMs),
                )
            }
        }
        return snapshot.map { item ->
            Row(
                title = item.title,
                subLine = "",
                isCurrent = item.index == currentIndex,
                seekTarget = PlayerChapters.SeekTarget(item.index, 0),
            )
        }
    }
}
