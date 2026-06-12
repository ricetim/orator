package com.orator.feature.player

import com.orator.core.database.BookEntity
import com.orator.core.database.BookmarkEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.designsystem.text.ShowNotes

/** What the loaded media item *is*, resolved from the DB. Drives the player's pages. */
sealed interface NowPlayingContent {
    data object Empty : NowPlayingContent

    data class Book(
        val book: BookEntity,
        val chapters: List<ChapterEntity>,
        val bookmarks: List<BookmarkEntity>,
    ) : NowPlayingContent

    data class Episode(
        val episode: EpisodeEntity,
        val podcast: PodcastEntity?,
        val notes: ShowNotes.Rendered?,
        val transcript: String?,
    ) : NowPlayingContent
}
