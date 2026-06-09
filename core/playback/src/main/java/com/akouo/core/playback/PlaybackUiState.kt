package com.akouo.core.playback

/** Immutable snapshot of what the player UI needs to render. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val title: String = "",
)
