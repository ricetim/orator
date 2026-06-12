package com.orator.feature.audiobooks.data
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PositionMapper

import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayableItem

/**
 * Turns a book + chapters + global start position into a PlayRequest.
 * M4B: one queue item (chapters are seek targets inside it), global position == file position.
 * MP3_DIR: one queue item per file; PositionMapper finds the starting (file, offset).
 */
object QueueBuilder {

    fun build(book: BookEntity, chapters: List<ChapterEntity>, startAtMs: Long): PlayRequest =
        when (book.sourceKind) {
            SourceKind.M4B -> PlayRequest(
                items = listOf(
                    PlayableItem(
                        mediaId = AudiobookMediaId.encode(book.id, 0),
                        uri = book.sourceUri,
                        title = book.title,
                        artist = book.author.orEmpty(),
                    ),
                ),
                startIndex = 0,
                startPositionMs = startAtMs,
                mediaType = MediaType.AUDIOBOOK,
                chapterBoundariesMs = chapters.map { it.startMs }.filter { it > 0 },
                speedOverride = book.speedOverride,
            )

            SourceKind.MP3_DIR -> {
                val start = PositionMapper.toFilePosition(chapters.map { it.durationMs }, startAtMs)
                PlayRequest(
                    items = chapters.map { chapter ->
                        PlayableItem(
                            mediaId = AudiobookMediaId.encode(book.id, chapter.chapterIndex),
                            uri = chapter.fileUri,
                            title = chapter.title,
                            artist = book.author.orEmpty(),
                        )
                    },
                    startIndex = start.fileIndex,
                    startPositionMs = start.offsetMs,
                    mediaType = MediaType.AUDIOBOOK,
                    speedOverride = book.speedOverride,
                )
            }
        }
}
