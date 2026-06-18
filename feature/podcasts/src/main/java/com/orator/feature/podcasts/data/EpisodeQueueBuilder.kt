package com.orator.feature.podcasts.data
import com.orator.core.playback.ids.PodcastMediaId

import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlayRequest
import com.orator.core.playback.PlayableItem

/**
 * Episode → single-item PlayRequest. Clip windows come from the show's intro/outro settings;
 * positions everywhere downstream are clip-relative (Phase 3 invariant). No outro clip when
 * the duration is unknown — the position listener backfills it after first play.
 */
object EpisodeQueueBuilder {

    fun build(podcast: PodcastEntity, episode: EpisodeEntity, startAtMs: Long): PlayRequest {
        val clipEnd = if (episode.durationMs > 0 && podcast.clipOutroMs > 0) {
            (episode.durationMs - podcast.clipOutroMs).coerceAtLeast(podcast.clipIntroMs + 1_000)
        } else {
            null
        }
        return PlayRequest(
            items = listOf(
                PlayableItem(
                    mediaId = PodcastMediaId.encode(episode.id),
                    uri = episode.audioPath ?: episode.enclosureUrl,
                    title = episode.title,
                    artist = podcast.title,
                    clipStartMs = podcast.clipIntroMs,
                    clipEndMs = clipEnd,
                ),
            ),
            startPositionMs = startAtMs,
            mediaType = MediaType.PODCAST,
            speedOverride = podcast.speedOverride,
        )
    }
}
