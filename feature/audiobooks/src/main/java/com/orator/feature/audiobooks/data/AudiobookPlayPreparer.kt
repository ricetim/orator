package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.model.BookDetailResolver
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.SmartRewind
import javax.inject.Inject

/**
 * Resolves a book's playable detail (origin-matched resolver) then builds a cold-start PlayRequest
 * with smart-rewind. The resolve step is what makes streaming an un-downloaded ABS book work —
 * every play path must go through here (or AudiobookPlayRequestFactory for playlists).
 */
class AudiobookPlayPreparer @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val detailResolvers: Set<@JvmSuppressWildcards BookDetailResolver>,
) {
    suspend fun prepare(bookId: String, prefs: PlayerPrefs): PlayRequest? {
        val initial = bookDao.getById(bookId) ?: return null
        detailResolvers.firstOrNull { it.handles(initial.origin) }?.ensureDetails(bookId)
        val book = bookDao.getById(bookId) ?: return null
        val rewind = if (prefs.smartRewind[MediaType.AUDIOBOOK] == true && book.lastPlayedAtMs > 0) {
            SmartRewind.rewindMs(System.currentTimeMillis() - book.lastPlayedAtMs)
        } else {
            0
        }
        return QueueBuilder.build(book, chapterDao.getForBook(bookId), (book.positionMs - rewind).coerceAtLeast(0))
    }
}
