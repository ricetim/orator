package com.orator.feature.audiobooks.data

import com.orator.core.database.BookDao
import com.orator.core.database.ChapterDao
import com.orator.core.database.SourceKind
import com.orator.core.playback.PlaybackPositionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Receives position pings from PlaybackService (every 3 s while playing + once on pause)
 * and persists the book's global resume position. Non-audiobook mediaIds are ignored —
 * other features get their own listeners.
 */
class AudiobookPositionListener @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
) : PlaybackPositionListener {

    override suspend fun onPositionChanged(mediaId: String, positionMs: Long, durationMs: Long) {
        val parsed = AudiobookMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            val book = bookDao.getById(parsed.bookId) ?: return@withContext
            val global = when (book.sourceKind) {
                SourceKind.M4B -> positionMs
                SourceKind.MP3_DIR -> PositionMapper.toGlobal(
                    chapterDao.getForBook(book.id).map { it.durationMs },
                    parsed.fileIndex,
                    positionMs,
                )
            }
            bookDao.updatePosition(book.id, global)
        }
    }
}
