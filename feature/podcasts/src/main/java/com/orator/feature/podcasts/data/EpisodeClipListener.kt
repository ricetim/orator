package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastDao
import com.orator.core.playback.ClipOverrideListener
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.ids.PodcastMediaId
import javax.inject.Inject

/**
 * Persists intro/outro changes for the playing episode's show and rebuilds the live queue so
 * the new clips apply immediately (user decision 2026-06-10). Position is clip-relative:
 * shift by the intro delta so the same audio moment keeps playing.
 */
class EpisodeClipListener @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    // Lazy is REQUIRED: PlaybackConnection's constructor takes Set<ClipOverrideListener>,
    // so a direct injection here is a guaranteed Dagger cycle. Lazy breaks the edge.
    private val playbackConnection: dagger.Lazy<PlaybackConnection>,
) : ClipOverrideListener {

    override suspend fun onClipChanged(mediaId: String, introMs: Long, outroMs: Long) {
        val episodeId = PodcastMediaId.parse(mediaId) ?: return
        val episode = episodeDao.getById(episodeId) ?: return
        val before = podcastDao.getById(episode.podcastId) ?: return
        podcastDao.updateClips(episode.podcastId, introMs.coerceAtLeast(0), outroMs.coerceAtLeast(0))
        val updated = podcastDao.getById(episode.podcastId) ?: return
        val connection = playbackConnection.get()
        val position = (connection.state.value.positionMs +
            before.clipIntroMs - updated.clipIntroMs).coerceAtLeast(0)
        connection.play(EpisodeQueueBuilder.build(updated, episode, position))
    }
}
