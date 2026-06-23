package com.orator.feature.audiobookshelf.data

import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind

/** Detail derived from an expanded ABS item, ready to persist as a book's playable layout. */
data class AbsBookDetail(
    val sourceKind: SourceKind,
    val sourceUri: String,
    val chapters: List<ChapterEntity>,
)

object AbsItemDetailMapper {
    fun map(item: AbsLibraryItem, baseUrl: String): AbsBookDetail {
        val bookId = "abs:${item.id}"
        val files = item.media.audioFiles.sortedBy { it.index }
        fun url(ino: String) = AbsUrl.endpoint(baseUrl, "api/items/${item.id}/file/$ino")

        return if (files.size <= 1) {
            val ino = files.firstOrNull()?.ino ?: ""
            val uri = url(ino)
            val chapters = item.media.chapters.mapIndexed { i, c ->
                ChapterEntity(
                    bookId = bookId, chapterIndex = i, title = c.title.ifBlank { "Chapter ${i + 1}" },
                    fileUri = uri, startMs = (c.start * 1000).toLong(),
                    durationMs = ((c.end - c.start) * 1000).toLong(),
                )
            }
            AbsBookDetail(SourceKind.SINGLE_FILE, uri, chapters)
        } else {
            val chapters = files.mapIndexed { i, f ->
                ChapterEntity(
                    bookId = bookId, chapterIndex = i, title = "Track ${f.index}",
                    fileUri = url(f.ino), startMs = 0, durationMs = (f.duration * 1000).toLong(),
                )
            }
            AbsBookDetail(SourceKind.MULTI_FILE, url(files.first().ino), chapters)
        }
    }
}
