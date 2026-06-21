package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayRequestFactory
import javax.inject.Inject

/**
 * Builds a whole-book PlayRequest for a playlist item, resuming at the book's saved position.
 * Smart-rewind-on-resume is applied by PlaybackService when playback starts, so no manual
 * rewind here. [BookEntity.id] is a String PK, so [MediaRef.id] is the book id directly.
 */
class AudiobookPlayRequestFactory @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) : PlayRequestFactory {
    override val mediaType = MediaType.AUDIOBOOK

    override suspend fun create(ref: MediaRef): PlayRequest? {
        val book = bookDao.getById(ref.id) ?: return null
        val chapters = chapterDao.getForBook(ref.id)
        return QueueBuilder.build(book, chapters, startAtMs = book.positionMs)
    }
}
