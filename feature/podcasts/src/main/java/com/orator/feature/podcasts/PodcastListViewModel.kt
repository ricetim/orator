package com.orator.feature.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.EpisodeDao
import com.orator.core.database.PodcastEntity
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.feature.podcasts.data.PodcastRepository
import com.orator.feature.podcasts.data.PodcastsFolderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastListViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val folderStore: PodcastsFolderStore,
    episodeDao: EpisodeDao,
    playbackConnection: PlaybackConnection,
) : ViewModel() {

    val podcasts: StateFlow<List<PodcastEntity>> = repository.podcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** podcastId → newest episode pubDate, for tile sub-lines. */
    val latestPub: StateFlow<Map<String, Long>> = episodeDao.observeLatestPubDates()
        .map { rows -> rows.associate { it.podcastId to it.latestUtc } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val hasFolder: StateFlow<Boolean> = folderStore.treeUri.map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val busy: StateFlow<String?> = repository.busy

    val playback: StateFlow<PlaybackUiState> = playbackConnection.state

    /** One-shot result line ("Imported 42, 1 failed"); cleared on the next action. */
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun onFolderPicked(treeUri: String) {
        viewModelScope.launch { folderStore.setTreeUri(treeUri) }
    }

    // Add-feed lives on the Search screen; OPML import lives in Settings (PodcastsSettingsSection).

    fun onRefreshAll() {
        viewModelScope.launch {
            _lastResult.value = null
            val summary = repository.refreshAll()
            _lastResult.value = "Refreshed ${summary.refreshed}, ${summary.failed} failed"
        }
    }
}
