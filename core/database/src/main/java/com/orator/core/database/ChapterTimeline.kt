package com.orator.core.database

/**
 * Pure math over a book's contiguous chapters. Chapters are assumed ordered by chapterIndex
 * and to tile the book end-to-end (each file's first chapter starts at 0; see the importer).
 * Files are the distinct fileUris in order; a file's duration is the sum of its chapters'.
 */
object ChapterTimeline {

    /** Ordered distinct files. */
    fun files(chapters: List<ChapterEntity>): List<String> =
        chapters.map { it.fileUri }.distinct()

    /** Per-file durations (sum of each file's chapter durations), in file order. */
    fun fileDurations(chapters: List<ChapterEntity>): List<Long> =
        files(chapters).map { uri -> chapters.filter { it.fileUri == uri }.sumOf { it.durationMs } }

    /** File index (queue item index) of the chapter at list position [chapterIndex]. */
    fun fileIndexOf(chapters: List<ChapterEntity>, chapterIndex: Int): Int =
        files(chapters).indexOf(chapters[chapterIndex].fileUri)

    /** Global start = sum of preceding chapter durations. */
    fun globalStartOf(chapters: List<ChapterEntity>, chapterIndex: Int): Long =
        chapters.take(chapterIndex).sumOf { it.durationMs }

    /** List position of the chapter whose [start, start+dur) contains globalMs; clamps to last. */
    fun chapterAtGlobal(chapters: List<ChapterEntity>, globalMs: Long): Int {
        if (chapters.isEmpty()) return 0
        var acc = 0L
        chapters.forEachIndexed { i, c ->
            acc += c.durationMs
            if (globalMs < acc) return i
        }
        return chapters.lastIndex
    }
}
