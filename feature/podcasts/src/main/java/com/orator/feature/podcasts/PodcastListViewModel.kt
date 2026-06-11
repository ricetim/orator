package com.orator.feature.podcasts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.PodcastEntity
import com.orator.core.playback.PlaybackConnection
import com.orator.core.playback.PlaybackUiState
import com.orator.feature.podcasts.data.PodcastRepository
import com.orator.feature.podcasts.data.PodcastsFolderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PodcastListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val folderStore: PodcastsFolderStore,
    playbackConnection: PlaybackConnection,
) : ViewModel() {

    val podcasts: StateFlow<List<PodcastEntity>> = repository.podcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun onAddFeed(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _lastResult.value = null
            _lastResult.value = repository.subscribe(url.trim()).fold(
                onSuccess = { "Subscribed" },
                onFailure = { "Failed: ${it.message}" },
            )
        }
    }

    fun onImportOpml(uri: Uri) {
        viewModelScope.launch {
            _lastResult.value = null
            val xml = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }
            }.getOrNull()
            if (xml == null) {
                _lastResult.value = "Could not read OPML"
                return@launch
            }
            val summary = repository.importOpml(xml)
            _lastResult.value = "Imported ${summary.refreshed}, ${summary.failed} failed"
        }
    }

    fun onRefreshAll() {
        viewModelScope.launch {
            _lastResult.value = null
            val summary = repository.refreshAll()
            _lastResult.value = "Refreshed ${summary.refreshed}, ${summary.failed} failed"
        }
    }
}
