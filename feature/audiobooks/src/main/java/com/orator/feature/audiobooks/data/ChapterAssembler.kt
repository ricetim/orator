package com.orator.feature.audiobooks.data

import com.orator.core.database.ChapterEntity

/**
 * Builds a book's contiguous chapter rows from its files. Each file contributes its `chpl`
 * marks (or one whole-file chapter via [FileChapters.fallbackTitle] when it has none); the
 * first chapter of every file is anchored to 0 so chapters tile each file — and thus the whole
 * book — end to end. `chapterIndex` is global across files. See ChapterTimeline for the math
 * that consumes these rows.
 */
object ChapterAssembler {

    data class FileChapters(
        val fileUri: String,
        val durationMs: Long,
        val marks: List<Mp4ChapterParser.Chapter>,
        val fallbackTitle: String,
    )

    fun assemble(bookId: String, files: List<FileChapters>): List<ChapterEntity> {
        val out = mutableListOf<ChapterEntity>()
        var index = 0
        for (f in files) {
            val marks = f.marks
                .ifEmpty { listOf(Mp4ChapterParser.Chapter(f.fallbackTitle, 0)) }
                .sortedBy { it.startMs }
                .toMutableList()
            // Anchor the file's first chapter to 0 so it owns any [0, firstMark) lead-in.
            if (marks.first().startMs != 0L) marks[0] = marks[0].copy(startMs = 0)
            marks.forEachIndexed { i, mark ->
                val end = marks.getOrNull(i + 1)?.startMs ?: f.durationMs
                out.add(
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = index++,
                        title = mark.title,
                        fileUri = f.fileUri,
                        startMs = mark.startMs,
                        durationMs = (end - mark.startMs).coerceAtLeast(0),
                    ),
                )
            }
        }
        return out
    }
}
