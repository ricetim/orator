package com.orator.feature.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orator.core.database.PlaylistSummary
import com.orator.core.model.MediaRef
import com.orator.core.model.MediaType
import com.orator.feature.playlists.data.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Parsed from nav args; null if the type is malformed (never crashes). */
    private val ref: MediaRef? = runCatching {
        MediaRef(
            type = MediaType.valueOf(checkNotNull(savedStateHandle["mediaType"])),
            id = checkNotNull(savedStateHandle["mediaId"]),
        )
    }.getOrNull()

    val playlists: StateFlow<List<PlaylistSummary>> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(playlistId: Long) {
        val target = ref ?: return
        viewModelScope.launch { repository.addToBottom(playlistId, target) }
    }

    fun createAndAdd(name: String) {
        val target = ref ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createPlaylist(name, System.currentTimeMillis())
            repository.addToBottom(id, target)
        }
    }
}
