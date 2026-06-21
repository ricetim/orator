package com.orator.feature.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.feature.playlists.data.PlaylistItemUi
import com.orator.feature.playlists.data.PlaylistRepository
import com.orator.feature.playlists.playback.PlaylistPlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val controller: PlaylistPlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])

    val name: StateFlow<String> = repository.observePlaylists()
        .map { list -> list.firstOrNull { it.id == playlistId }?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val items: StateFlow<List<PlaylistItemUi>> = repository.observeItems(playlistId)
        .map { repository.items(playlistId) } // re-hydrate (prunes dangling rows) on every change
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playFromTop() = launchUnit { controller.playFromTop(playlistId) }
    fun playItem(itemId: Long) = launchUnit { controller.playItem(playlistId, itemId) }
    fun remove(itemId: Long) = launchUnit { repository.removeItem(itemId) }
    fun move(from: Int, to: Int) = launchUnit { repository.move(playlistId, from, to) }
    fun rename(name: String) = launchUnit { repository.renamePlaylist(playlistId, name) }
    fun delete() = launchUnit { repository.deletePlaylist(playlistId) }

    private inline fun launchUnit(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
