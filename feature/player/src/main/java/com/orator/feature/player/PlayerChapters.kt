package com.orator.feature.player

import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import com.orator.core.playback.ids.PositionMapper

/**
 * Pure chapter math for the unified player. M4B chapters are offsets inside one queue item;
 * MP3_DIR chapters ARE the queue items. Everything the chapter bar, tick marks, ⏮/⏭ and
 * chapter taps need, with no Android or playback dependencies.
 */
object PlayerChapters {

    /** Where to seek: queue item [index] + [positionMs] inside it (PlaybackConnection.seekTo). */
    data class SeekTarget(val index: Int, val positionMs: Long)

    data class ChapterUi(
        val index: Int,
        val count: Int,
        val title: String,
        val positionInChapterMs: Long,
        val chapterDurationMs: Long,
    )

    private const val RESTART_THRESHOLD_MS = 3_000L

    fun current(
        chapters: List<ChapterEntity>,
        sourceKind: SourceKind,
        currentIndex: Int,
        positionMs: Long,
        totalDurationMs: Long,
    ): ChapterUi? {
        if (chapters.isEmpty()) return null
        return when (sourceKind) {
            SourceKind.M4B -> {
                val sorted = chapters.sortedBy { it.startMs }
                val i = sorted.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
                val start = sorted[i].startMs
                val end = sorted.getOrNull(i + 1)?.startMs ?: totalDurationMs
                ChapterUi(i, sorted.size, sorted[i].title, positionMs - start, end - start)
            }
            SourceKind.MP3_DIR -> {
                val c = chapters.getOrNull(currentIndex) ?: return null
                ChapterUi(currentIndex, chapters.size, c.title, positionMs, c.durationMs)
            }
        }
    }

    fun next(
        chapters: List<ChapterEntity>,
        sourceKind: SourceKind,
        currentIndex: Int,
        positionMs: Long,
    ): SeekTarget? {
        val cur = current(chapters, sourceKind, currentIndex, positionMs, Long.MAX_VALUE) ?: return null
        if (cur.index >= chapters.size - 1) return null
        return tap(chapters, sourceKind, cur.index + 1)
    }

    fun previous(
        chapters: List<ChapterEntity>,
        sourceKind: SourceKind,
        currentIndex: Int,
        positionMs: Long,
    ): SeekTarget? {
        val cur = current(chapters, sourceKind, currentIndex, positionMs, Long.MAX_VALUE) ?: return null
        val target = if (cur.positionInChapterMs > RESTART_THRESHOLD_MS || cur.index == 0) {
            cur.index
        } else {
            cur.index - 1
        }
        return tap(chapters, sourceKind, target)
    }

    fun tap(chapters: List<ChapterEntity>, sourceKind: SourceKind, chapterIndex: Int): SeekTarget =
        when (sourceKind) {
            SourceKind.M4B ->
                SeekTarget(0, chapters.sortedBy { it.startMs }[chapterIndex].startMs)
            SourceKind.MP3_DIR -> SeekTarget(chapterIndex, 0)
        }

    /** Whole-item progress 0..1 (the "Book"/"Episode" bar). */
    fun itemFraction(
        chapters: List<ChapterEntity>,
        sourceKind: SourceKind,
        currentIndex: Int,
        positionMs: Long,
        totalDurationMs: Long,
    ): Float {
        if (totalDurationMs <= 0) return 0f
        val global = when (sourceKind) {
            SourceKind.M4B -> positionMs
            SourceKind.MP3_DIR ->
                PositionMapper.toGlobal(chapters.map { it.durationMs }, currentIndex, positionMs)
        }
        return (global.toFloat() / totalDurationMs).coerceIn(0f, 1f)
    }

    /** Chapter-start tick fractions for the whole-item bar (0 excluded). */
    fun ticks(
        chapters: List<ChapterEntity>,
        sourceKind: SourceKind,
        totalDurationMs: Long,
    ): List<Float> {
        if (totalDurationMs <= 0) return emptyList()
        val starts = when (sourceKind) {
            SourceKind.M4B -> chapters.map { it.startMs }.sorted()
            SourceKind.MP3_DIR -> chapters.map { it.durationMs }
                .runningFold(0L) { acc, d -> acc + d }.dropLast(1)
        }
        return starts.filter { it > 0 }.map { it.toFloat() / totalDurationMs }
    }

    /** Drag/tap on the whole-item bar → seek target. */
    fun itemSeek(
        chapters: List<ChapterEntity>,
        sourceKind: SourceKind,
        fraction: Float,
        totalDurationMs: Long,
    ): SeekTarget {
        val globalMs = (fraction.coerceIn(0f, 1f) * totalDurationMs).toLong()
        return when (sourceKind) {
            SourceKind.M4B -> SeekTarget(0, globalMs)
            SourceKind.MP3_DIR -> {
                val p = PositionMapper.toFilePosition(chapters.map { it.durationMs }, globalMs)
                SeekTarget(p.fileIndex, p.offsetMs)
            }
        }
    }
}
