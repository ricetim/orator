package com.orator.feature.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
import com.orator.core.model.MediaType
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.core.playback.PlayerPreferences
import com.orator.core.playback.PlayerPrefs
import com.orator.core.playback.SmartRewind
import com.orator.core.playback.ids.PodcastMediaId
import com.orator.feature.podcasts.data.EpisodeDownloader
import com.orator.feature.podcasts.data.EpisodeQueueBuilder
import com.orator.feature.podcasts.data.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val downloader: EpisodeDownloader,
    private val playerPreferences: PlayerPreferences,
) : ViewModel() {

    private val podcastId: String = checkNotNull(savedStateHandle["podcastId"])

    val podcast: StateFlow<PodcastEntity?> = podcastDao.observeById(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<EpisodeEntity>> = episodeDao.observeForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadProgress: StateFlow<Map<String, Float>> = downloader.progress

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    val prefs: StateFlow<PlayerPrefs> = playerPreferences.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerPrefs())

    fun onDownload(episodeId: String) = downloader.enqueue(episodeId)

    fun onCancelDownload(episodeId: String) = downloader.cancel(episodeId)

    fun onDeleteDownload(episodeId: String) {
        viewModelScope.launch { downloader.deleteDownload(episodeId) }
    }

    /** Row tap: start (or keep) playback of this episode, then let the UI navigate. */
    fun onPlayEpisode(episodeId: String, onStarted: () -> Unit) {
        viewModelScope.launch {
            if (playback.value.mediaId == PodcastMediaId.encode(episodeId)) {
                if (!playback.value.isPlaying) playbackConnection.playPause()
                onStarted() // already loaded — just open the player
                return@launch
            }
            val e = episodeDao.getById(episodeId) ?: return@launch
            val show = podcastDao.getById(podcastId) ?: return@launch
            // Cold-start smart rewind — same tiers as the old EpisodeDetailViewModel.
            val p = playerPreferences.flow.first()
            val rewind = if (p.smartRewind[MediaType.PODCAST] == true && e.lastPlayedAtMs > 0) {
                SmartRewind.rewindMs(System.currentTimeMillis() - e.lastPlayedAtMs)
            } else {
                0
            }
            playbackConnection.play(
                EpisodeQueueBuilder.build(show, e, (e.positionMs - rewind).coerceAtLeast(0)),
            )
            onStarted()
        }
    }

    /** Effects-sheet callbacks (sheet semantics per the spec). */
    fun onTypeSpeed(speed: Float) {
        viewModelScope.launch { playerPreferences.setTypeSpeed(MediaType.PODCAST, speed) }
    }

    fun onTrim(on: Boolean) {
        viewModelScope.launch { playerPreferences.setSilenceTrim(on) }
    }

    fun onBoost(mb: Int) {
        viewModelScope.launch { playerPreferences.setBoostMb(mb.coerceIn(0, 1500)) }
    }

    /**
     * Steps are whole seconds in the UI; stored as ms. If an episode of THIS show is loaded in
     * the player, EpisodeClipListener persists + rebuilds the queue in place so the new clips
     * apply immediately (user decision 2026-06-10); otherwise just persist.
     */
    fun onClipChange(introMs: Long, outroMs: Long) {
        viewModelScope.launch {
            if (activeEpisodeOfThisShow() != null) {
                playbackConnection.setClipOverride(introMs, outroMs)
            } else {
                podcastDao.updateClips(podcastId, introMs.coerceAtLeast(0), outroMs.coerceAtLeast(0))
            }
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
}
