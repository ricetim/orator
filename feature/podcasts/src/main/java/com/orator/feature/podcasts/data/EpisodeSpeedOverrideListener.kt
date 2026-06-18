package com.orator.feature.podcasts.data
import com.orator.core.playback.ids.PodcastMediaId

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.playback.SpeedOverrideListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Speed overrides are per-SHOW (spec decision): setting one while playing any episode sticks for the whole podcast. */
class EpisodeSpeedOverrideListener @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) : SpeedOverrideListener {

    override suspend fun onSpeedOverrideChanged(mediaId: String, speed: Float?) {
        val episodeId = PodcastMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            val episode = episodeDao.getById(episodeId) ?: return@withContext
            podcastDao.updateSpeedOverride(episode.podcastId, speed)
        }
    }
}
