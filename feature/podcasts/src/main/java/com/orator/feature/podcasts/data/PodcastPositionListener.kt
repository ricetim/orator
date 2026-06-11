package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.playback.PlaybackPositionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Persists clip-relative resume positions for episodes. Also backfills the original-timeline
 * duration after first play: the player reports the CLIPPED duration, and backfill only fires
 * when durationMs == 0 — in that case no outro clip was applied, so original = player + intro
 * (spec "Duration backfill rule"). The never-overwrite guard lives in EpisodeDao SQL.
 */
class PodcastPositionListener @Inject constructor(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
) : PlaybackPositionListener {

    override suspend fun onPositionChanged(mediaId: String, positionMs: Long, durationMs: Long) {
        val episodeId = PodcastMediaId.parse(mediaId) ?: return
        withContext(Dispatchers.IO) {
            val episode = episodeDao.getById(episodeId) ?: return@withContext
            episodeDao.updateProgress(episodeId, positionMs, System.currentTimeMillis())
            if (episode.durationMs == 0L && durationMs > 0) {
                val intro = podcastDao.getById(episode.podcastId)?.clipIntroMs ?: 0
                episodeDao.backfillDuration(episodeId, durationMs + intro)
            }
        }
    }
}
