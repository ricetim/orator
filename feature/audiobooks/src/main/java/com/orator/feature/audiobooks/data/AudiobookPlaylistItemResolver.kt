package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import javax.inject.Inject

/** Resolves an audiobook playlist item to its display fields. */
class AudiobookPlaylistItemResolver @Inject constructor(
    private val bookDao: BookDao,
) : PlaylistItemResolver {
    override val mediaType = MediaType.AUDIOBOOK

    override suspend fun resolve(ref: MediaRef): PlaylistItemContent? {
        val book = bookDao.getById(ref.id) ?: return null
        return PlaylistItemContent(
            title = book.title,
            subtitle = book.author.orEmpty(),
            artworkUri = book.coverPath,
            durationMs = book.durationMs,
        )
    }
}
