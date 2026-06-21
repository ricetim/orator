package com.orator.feature.playlists.data

import com.orator.core.database.EpisodeDao
import com.orator.core.database.PlaylistDao
import com.orator.core.database.PodcastDao
import com.orator.core.model.AutoInsertRule
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.core.playback.NewEpisodeListener
import javax.inject.Inject

/**
 * Auto-insert side of the [NewEpisodeListener] seam: when a refresh discovers new episodes, route
 * each into its podcast's configured playlist (top/bottom). Reads the per-podcast config via the
 * shared core:database DAOs — this never imports feature:podcasts. Dedupe + dangling-target are
 * handled gracefully (unique index ignores re-adds; a deleted target is skipped).
 */
class PlaylistAutoInserter @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val podcastDao: PodcastDao,
    private val repository: PlaylistRepository,
    private val playlistDao: PlaylistDao,
) : NewEpisodeListener {

    override suspend fun onNewEpisodes(episodeIds: List<String>) {
        for (id in episodeIds) {
            val episode = episodeDao.getById(id) ?: continue
            val podcast = podcastDao.getById(episode.podcastId) ?: continue
            val target = podcast.autoInsertPlaylistId ?: continue
            val rule = podcast.autoInsertRule ?: continue
            if (playlistDao.getPlaylist(target) == null) continue // dangling target → treat as off
            val ref = MediaRef(MediaType.PODCAST, id)
            when (rule) {
                AutoInsertRule.NEW_TO_TOP -> repository.addAtTop(target, ref)
                AutoInsertRule.NEW_TO_BOTTOM -> repository.addToBottom(target, ref)
            }
        }
    }
}
