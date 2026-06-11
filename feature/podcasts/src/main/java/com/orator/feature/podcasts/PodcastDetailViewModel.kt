package com.orator.feature.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import com.orator.core.playback.PlaybackConnection
import com.orator.feature.podcasts.data.EpisodeQueueBuilder
import com.orator.feature.podcasts.data.PodcastMediaId
import com.orator.feature.podcasts.data.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val playbackConnection: PlaybackConnection,
    private val repository: PodcastRepository,
) : ViewModel() {

    private val podcastId: String = checkNotNull(savedStateHandle["podcastId"])

    val podcast: StateFlow<PodcastEntity?> = podcastDao.observeById(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<EpisodeEntity>> = episodeDao.observeForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Steps are whole seconds in the UI; stored as ms. If an episode of THIS show is loaded in
     * the player, the queue is rebuilt in place so the new clips apply immediately (user
     * decision 2026-06-10: clip changes apply to everything, including current playback).
     */
    fun onClipChange(introMs: Long, outroMs: Long) {
        viewModelScope.launch {
            val before = podcastDao.getById(podcastId) ?: return@launch
            podcastDao.updateClips(podcastId, introMs.coerceAtLeast(0), outroMs.coerceAtLeast(0))
            rebuildActiveQueue(previousIntroMs = before.clipIntroMs)
        }
    }

    fun onSpeedOverride(speed: Float?) {
        viewModelScope.launch {
            val rounded = speed?.let { (it.coerceIn(0.5f, 3.0f) * 100).toInt() / 100f }
            if (activeEpisodeOfThisShow() != null) {
                // Applies to live playback; persistence happens via EpisodeSpeedOverrideListener.
                playbackConnection.setSpeedOverride(rounded)
            } else {
                podcastDao.updateSpeedOverride(podcastId, rounded)
            }
        }
    }

    fun onUnsubscribe(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.unsubscribe(podcastId)
            onDone()
        }
    }

    /** The loaded episode, but only when it belongs to this show. */
    private suspend fun activeEpisodeOfThisShow(): EpisodeEntity? {
        val mediaId = playbackConnection.state.value.mediaId ?: return null
        val episodeId = PodcastMediaId.parse(mediaId) ?: return null
        return episodeDao.getById(episodeId)?.takeIf { it.podcastId == podcastId }
    }

    private suspend fun rebuildActiveQueue(previousIntroMs: Long) {
        val episode = activeEpisodeOfThisShow() ?: return
        val updated = podcastDao.getById(podcastId) ?: return
        // Positions are clip-relative: shift by the intro delta so the same audio moment keeps
        // playing; Media3 clamps if the point now falls outside the new clip window.
        val position = (playbackConnection.state.value.positionMs +
            previousIntroMs - updated.clipIntroMs).coerceAtLeast(0)
        playbackConnection.play(EpisodeQueueBuilder.build(updated, episode, position))
    }
}
