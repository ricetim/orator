package com.orator.feature.player

import com.orator.core.database.HistoryDao
import com.orator.core.database.HistoryEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Persists play history from service events (PlaybackEventListener @IntoSet — the same seam
 * positions use, keeping core:playback Room-free). Rows whose session was interrupted keep
 * endedAtUtc == null; we never invent an end time.
 */
class HistoryRecorder @Inject constructor(
    private val historyDao: HistoryDao,
) : PlaybackEventListener {

    override suspend fun onItemStarted(mediaId: String, title: String, mediaType: MediaType?) {
        withContext(Dispatchers.IO) {
            historyDao.insert(
                HistoryEntity(
                    mediaId = mediaId,
                    title = title,
                    mediaType = mediaType?.name,
                    startedAtUtc = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun onItemEnded(mediaId: String, positionMs: Long, completed: Boolean) {
        withContext(Dispatchers.IO) {
            historyDao.close(mediaId, endedAtUtc = System.currentTimeMillis(), completed = completed)
        }
    }
}
