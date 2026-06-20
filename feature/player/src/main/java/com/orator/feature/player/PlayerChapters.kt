package com.orator.feature.player

import com.orator.core.database.ChapterEntity
import com.orator.core.database.ChapterTimeline
import com.orator.core.database.SourceKind
import com.orator.core.playback.ids.PositionMapper

/**
 * Pure chapter math for the unified player. SINGLE_FILE chapters are offsets inside one queue
 * item. MULTI_FILE chapters tile a multi-file timeline (possibly several chapters per file);
 * [currentIndex] is the playing file (queue item) and [positionMs] is the offset within it, so
 * the math converts to/from a global position via ChapterTimeline + PositionMapper. Everything
 * the chapter bar, tick marks, ⏮/⏭ and chapter taps need, with no Android/playback deps.
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
            SourceKind.SINGLE_FILE -> {
                val sorted = chapters.sortedBy { it.startMs }
                val i = sorted.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
                val start = sorted[i].startMs
                val end = sorted.getOrNull(i + 1)?.startMs ?: totalDurationMs
                ChapterUi(i, sorted.size, sorted[i].title, positionMs - start, end - start)
            }
            SourceKind.MULTI_FILE -> {
                val global = globalOf(chapters, currentIndex, positionMs)
                val i = ChapterTimeline.chapterAtGlobal(chapters, global)
                val start = ChapterTimeline.globalStartOf(chapters, i)
                ChapterUi(i, chapters.size, chapters[i].title, global - start, chapters[i].durationMs)
            }
        }
    }

    /** Global position for a MULTI_FILE book from its (file index, in-file offset). */
    private fun globalOf(chapters: List<ChapterEntity>, fileIndex: Int, positionMs: Long): Long =
        PositionMapper.toGlobal(ChapterTimeline.fileDurations(chapters), fileIndex, positionMs)

    /** 1-based chapter number containing a GLOBAL position; null when chapters are unknown. */
    fun chapterNumberAt(chapters: List<ChapterEntity>, sourceKind: SourceKind, globalMs: Long): Int? {
        if (chapters.isEmpty()) return null
        return when (sourceKind) {
            SourceKind.SINGLE_FILE ->
                chapters.sortedBy { it.startMs }.indexOfLast { it.startMs <= globalMs }.coerceAtLeast(0) + 1
            SourceKind.MULTI_FILE -> ChapterTimeline.chapterAtGlobal(chapters, globalMs) + 1
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
            SourceKind.SINGLE_FILE ->
                SeekTarget(0, chapters.sortedBy { it.startMs }[chapterIndex].startMs)
            SourceKind.MULTI_FILE ->
                SeekTarget(ChapterTimeline.fileIndexOf(chapters, chapterIndex), chapters[chapterIndex].startMs)
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
            SourceKind.SINGLE_FILE -> positionMs
            SourceKind.MULTI_FILE -> globalOf(chapters, currentIndex, positionMs)
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
            SourceKind.SINGLE_FILE -> chapters.map { it.startMs }.sorted()
            SourceKind.MULTI_FILE -> chapters.indices.map { ChapterTimeline.globalStartOf(chapters, it) }
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
            SourceKind.SINGLE_FILE -> SeekTarget(0, globalMs)
            SourceKind.MULTI_FILE -> {
                val p = PositionMapper.toFilePosition(ChapterTimeline.fileDurations(chapters), globalMs)
                SeekTarget(p.fileIndex, p.offsetMs)
            }
        }
    }
}
