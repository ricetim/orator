package com.orator.feature.audiobooks.data
import com.orator.core.playback.ids.AudiobookMediaId
import com.orator.core.playback.ids.PositionMapper

import com.orator.core.database.BookEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.ChapterTimeline
import com.orator.core.database.SourceKind
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayableItem

/**
 * Turns a book + chapters + global start position into a PlayRequest.
 * SINGLE_FILE: one queue item (chapters are seek targets inside it), global == file position.
 * MULTI_FILE: one queue item per file (derived by grouping chapters on fileUri); PositionMapper
 * maps the global start to a (file, offset) using per-file durations.
 */
object QueueBuilder {

    fun build(book: BookEntity, chapters: List<ChapterEntity>, startAtMs: Long): PlayRequest =
        when (book.sourceKind) {
            SourceKind.SINGLE_FILE -> PlayRequest(
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
                fileDurationsMs = listOf(book.durationMs),
                speedOverride = book.speedOverride,
            )

            SourceKind.MULTI_FILE -> {
                val files = ChapterTimeline.files(chapters)
                val fileDurations = ChapterTimeline.fileDurations(chapters)
                val start = PositionMapper.toFilePosition(fileDurations, startAtMs)
                PlayRequest(
                    items = files.mapIndexed { fileIndex, uri ->
                        PlayableItem(
                            mediaId = AudiobookMediaId.encode(book.id, fileIndex),
                            uri = uri,
                            // The file isn't one chapter; use its first chapter's title (for an
                            // mp3 track that IS the track title — preserves the old behavior).
                            title = chapters.first { it.fileUri == uri }.title,
                            artist = book.author.orEmpty(),
                        )
                    },
                    startIndex = start.fileIndex,
                    startPositionMs = start.offsetMs,
                    mediaType = MediaType.AUDIOBOOK,
                    chapterBoundariesMs = chapters.indices
                        .map { ChapterTimeline.globalStartOf(chapters, it) }
                        .filter { it > 0 },
                    fileDurationsMs = fileDurations,
                    speedOverride = book.speedOverride,
                )
            }
        }
}
