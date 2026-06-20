package com.orator.core.playback

import com.orator.core.model.MediaType

/** Immutable snapshot of what the player UI needs to render. */
data class PlaybackUiState(
    val isPlaying: Boolean = false,
    /** True when the player has played its whole queue to the end (Media3 STATE_ENDED). */
    val isEnded: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val mediaId: String? = null,
    /** Recovered from MediaMetadata.mediaType; null until something we built is loaded. */
    val mediaType: MediaType? = null,
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1.0f,
)
