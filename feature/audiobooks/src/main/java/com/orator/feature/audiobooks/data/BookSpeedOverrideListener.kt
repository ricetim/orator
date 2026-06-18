package com.orator.feature.audiobooks.data
import com.orator.core.playback.ids.AudiobookMediaId

import com.orator.core.database.BookDao
import com.orator.core.playback.SpeedOverrideListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Persists per-book speed overrides set from the player UI (which doesn't know about books). */
class BookSpeedOverrideListener @Inject constructor(
    private val bookDao: BookDao,
) : SpeedOverrideListener {

    override suspend fun onSpeedOverrideChanged(mediaId: String, speed: Float?) {
        val parsed = AudiobookMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            bookDao.updateSpeedOverride(parsed.bookId, speed)
        }
    }
}
