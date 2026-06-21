package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayRequestFactory
import javax.inject.Inject

/**
 * Builds an episode PlayRequest for a playlist item, resuming at the episode's saved position.
 * The episode carries no show metadata, so the parent podcast (clips, speed override) is loaded
 * too. Smart-rewind-on-resume is applied by PlaybackService at start, so no manual rewind here.
 */
class EpisodePlayRequestFactory @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
) : PlayRequestFactory {
    override val mediaType = MediaType.PODCAST

    override suspend fun create(ref: MediaRef): PlayRequest? {
        val episode = episodeDao.getById(ref.id) ?: return null
        val podcast = podcastDao.getById(episode.podcastId) ?: return null
        return EpisodeQueueBuilder.build(podcast, episode, startAtMs = episode.positionMs)
    }
}
