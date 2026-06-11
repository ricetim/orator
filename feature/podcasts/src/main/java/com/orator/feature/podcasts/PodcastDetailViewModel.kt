package com.orator.feature.podcasts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.EpisodeEntity
import com.orator.core.database.PodcastDao
import com.orator.core.database.PodcastEntity
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
    episodeDao: EpisodeDao,
) : ViewModel() {

    private val podcastId: String = checkNotNull(savedStateHandle["podcastId"])

    val podcast: StateFlow<PodcastEntity?> = podcastDao.observeById(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<EpisodeEntity>> = episodeDao.observeForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Steps are whole seconds in the UI; stored as ms. Applies on the NEXT play (placeholder UI). */
    fun onClipChange(introMs: Long, outroMs: Long) {
        viewModelScope.launch {
            podcastDao.updateClips(podcastId, introMs.coerceAtLeast(0), outroMs.coerceAtLeast(0))
        }
    }

    fun onSpeedOverride(speed: Float?) {
        viewModelScope.launch {
            podcastDao.updateSpeedOverride(
                podcastId,
                speed?.let { (it.coerceIn(0.5f, 3.0f) * 100).toInt() / 100f },
            )
        }
    }
}
