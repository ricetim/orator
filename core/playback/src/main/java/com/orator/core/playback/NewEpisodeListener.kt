package com.orator.core.playback

/**
 * Called after a feed refresh discovers brand-new episodes (manual refresh OR the background
 * worker). Contributed per consumer via Hilt @IntoSet, mirroring PlaybackEventListener: the
 * podcasts side emits, a feature implements. Phase 5b: feature:playlists auto-inserts the new
 * episodes into a configured playlist.
 */
interface NewEpisodeListener {
    suspend fun onNewEpisodes(episodeIds: List<String>)
}
