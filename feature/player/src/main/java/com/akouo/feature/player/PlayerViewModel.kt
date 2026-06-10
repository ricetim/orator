package com.akouo.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akouo.core.playback.PlaybackConnection
import com.akouo.core.playback.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    val uiState: StateFlow<PlaybackUiState> =
        playbackConnection.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaybackUiState(),
        )

    fun onLoadSampleClick() = playbackConnection.playBundledSample()

    fun onPlayPauseClick() = playbackConnection.playPause()
}
