package com.akouo.core.playback

/** Immutable snapshot of what the player UI needs to render. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val mediaId: String? = null,
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)
