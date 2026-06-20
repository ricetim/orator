package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.model.PlaylistItemContent
import com.orator.core.model.PlaylistItemResolver
import javax.inject.Inject

/** Resolves an episode playlist item to its display fields (show name + artwork come from the podcast). */
class EpisodePlaylistItemResolver @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
) : PlaylistItemResolver {
    override val mediaType = MediaType.PODCAST

    override suspend fun resolve(ref: MediaRef): PlaylistItemContent? {
        val episode = episodeDao.getById(ref.id) ?: return null
        val podcast = podcastDao.getById(episode.podcastId) ?: return null
        return PlaylistItemContent(
            title = episode.title,
            subtitle = podcast.title,
            artworkUri = podcast.artworkUrl,
            durationMs = episode.durationMs,
        )
    }
}
